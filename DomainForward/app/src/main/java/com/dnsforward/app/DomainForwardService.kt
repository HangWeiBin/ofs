package com.dnsforward.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Sets up a system VPN that captures nothing but the traffic towards the
 * current DNS server(s). Plain DNS (UDP 53) queries for configured domains are
 * answered directly with the user-chosen IP; every other query is forwarded to
 * the real DNS server and the answer is relayed back into the tunnel. All other
 * traffic keeps flowing through the normal network, so the phone's internet is
 * not routed through this VPN at all.
 */
class DomainForwardService : VpnService() {

    companion object {
        const val ACTION_START = "com.dnsforward.app.action.START"
        const val ACTION_STOP = "com.dnsforward.app.action.STOP"

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "forward_service"
        private const val TUN_IP = "10.86.0.1"
        private const val SESSION_IDLE_TIMEOUT_MS = 8000

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, DomainForwardService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DomainForwardService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopRequested = false

    @Volatile
    private var restartRequested = false

    @Volatile
    private var tunFd: ParcelFileDescriptor? = null

    @Volatile
    private var tunOut: FileOutputStream? = null

    private var currentDns: List<String> = emptyList()
    private var vpnThread: Thread? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val forwarder = Forwarder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            return START_NOT_STICKY
        }
        if (!isRunning) startEngine()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRequested = true
        closeTun()
        forwarder.stop()
        isRunning = false
        unregisterNetworkCallback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startEngine() {
        isRunning = true
        stopRequested = false
        restartRequested = false
        RulesStore.load(this)
        startAsForeground()
        registerNetworkCallback()
        vpnThread = thread(name = "vpn-loop") { vpnLoop() }
    }

    private fun stopEngine() {
        stopRequested = true
        closeTun()
        forwarder.stop()
        isRunning = false
        unregisterNetworkCallback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DomainForwardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.running_notif_title))
            .setContentText(getString(R.string.running_notif_text))
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_VPN
        )
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                maybeRestart()
            }

            override fun onLost(network: Network) {
                maybeRestart()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(netCallback!!, mainHandler) }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = netCallback ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        netCallback = null
    }

    /** Called on the main thread when the default network (and DNS) may have changed. */
    private fun maybeRestart() {
        if (stopRequested || restartRequested) return
        val now = fetchDns()
        if (now == currentDns) return
        restartRequested = true
        closeTun()
    }

    // -------------------------------------------------------------- VPN loop

    private fun vpnLoop() {
        while (!stopRequested) {
            RulesStore.load(this)
            val dns = fetchDns()
            if (dns.isEmpty()) {
                Thread.sleep(1500)
                continue
            }
            val fd = try {
                establishVpn(dns)
            } catch (e: Exception) {
                null
            }
            if (fd == null) {
                if (!stopRequested) toast(getString(R.string.service_failed))
                stopEngine()
                return
            }
            currentDns = dns
            restartRequested = false
            runReader(fd)
            closeTun()
            if (stopRequested) return
            if (!restartRequested) {
                // The tunnel went away without us asking (revoked from the shade).
                if (!stopRequested) toast(getString(R.string.vpn_revoked))
                stopEngine()
                return
            }
        }
    }

    private fun establishVpn(dns: List<String>): ParcelFileDescriptor? {
        val builder = Builder()
        builder.setSession(getString(R.string.app_name))
        builder.setMtu(1500)
        builder.addAddress(TUN_IP, 32)
        for (server in dns) {
            builder.addRoute(server, 32)
        }
        builder.setBlocking(true)
        return builder.establish()
    }

    private fun runReader(fd: ParcelFileDescriptor) {
        var input: FileInputStream? = null
        var output: FileOutputStream? = null
        try {
            input = FileInputStream(fd.fileDescriptor)
            output = FileOutputStream(fd.fileDescriptor)
            tunFd = fd
            tunOut = output
            forwarder.start(output)
            val buf = ByteArray(65535)
            while (!stopRequested && !restartRequested) {
                val n = input.read(buf)
                if (n <= 0) {
                    if (stopRequested || restartRequested) break
                    continue
                }
                handlePacket(buf, n)
            }
        } catch (_: IOException) {
            // Tunnel file descriptor closed: restart, stop or system revoke.
        } finally {
            forwarder.stop()
            tunOut = null
            runCatching { input?.close() }
            runCatching { output?.close() }
        }
    }

    private fun handlePacket(buf: ByteArray, n: Int) {
        val udp = VpnIp.parseUdp(buf, n) ?: return
        if (udp.dstPort != 53) return // only plain DNS over UDP
        val query = udp.payload
        val question = DnsPacket.parseQuery(query)
        val rule = question?.name?.let { name ->
            RulesStore.rules.firstOrNull { matches(it, name) }
        }
        val out = tunOut ?: return

        if (rule != null && question != null) {
            val ip = rule.inet ?: return
            val answer = DnsPacket.buildAnswer(query, question, ip) ?: return
            // The synthesized answer looks like it comes from the DNS server.
            val packet = VpnIp.buildUdp(udp.dstIp, udp.srcIp, udp.dstPort, udp.srcPort, answer)
            try {
                out.write(packet)
                out.flush()
            } catch (_: IOException) {
                // tunnel gone
            }
        } else {
            forwarder.forward(this, udp)
        }
    }

    private fun matches(rule: Rule, name: String): Boolean {
        val d = rule.domain.lowercase().trimEnd('.')
        return if (rule.sub) {
            name == d || name.endsWith(".$d")
        } else {
            name == d
        }
    }

    private fun fetchDns(): List<String> = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return emptyList()
        val lp = cm.getLinkProperties(net) ?: return emptyList()
        lp.dnsServers
            .mapNotNull { it.hostAddress }
            .filter { !it.contains(":") } // IPv4 DNS servers only
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun closeTun() {
        val fd = tunFd
        tunFd = null
        runCatching { fd?.close() }
    }

    // ------------------------------------------------------------------ forwarder

    /**
     * Relays plain DNS queries that do not match any rule to the real DNS server
     * and injects the answers back into the tunnel, rewriting the IP/UDP headers.
     */
    private inner class Forwarder {

        @Volatile
        private var output: FileOutputStream? = null

        private val sessions = ConcurrentHashMap<String, Session>()

        fun start(out: FileOutputStream) {
            output = out
        }

        fun stop() {
            output = null
            sessions.values.forEach { it.close() }
            sessions.clear()
        }

        fun forward(vpn: VpnService, udp: VpnIp.UdpPacket) {
            val key = "${VpnIp.addrString(udp.srcIp)}:${udp.srcPort}"
            val serverHost = VpnIp.addrString(udp.dstIp)
            var session = sessions[key]
            if (session == null || session.serverHost != serverHost || session.serverPort != udp.dstPort) {
                session?.close()
                session = Session(udp.srcIp, udp.srcPort, serverHost, udp.dstPort)
                sessions[key] = session
                runCatching { vpn.protect(session.socket) }
                session.receiverThread = thread(name = "dns-fwd-$key", isDaemon = true) {
                    receiveLoop(session)
                }
            }
            val payload = udp.payload
            try {
                session.socket.send(
                    DatagramPacket(payload, payload.size, InetAddress.getByAddress(udp.dstIp), udp.dstPort)
                )
            } catch (_: IOException) {
                session.close()
                sessions.remove(key, session)
            }
        }

        private fun receiveLoop(session: Session) {
            var idle = 0
            try {
                while (!session.closed) {
                    val datagram: DatagramPacket
                    try {
                        session.socket.receive(session.packet)
                        datagram = session.packet
                    } catch (e: SocketTimeoutException) {
                        idle++
                        if (idle >= 3) break
                        continue
                    }
                    idle = 0
                    val out = output ?: break
                    val len = datagram.length
                    val payload = datagram.data.copyOf(len)
                    // Answer travels back from the real server to the client.
                    val reply = VpnIp.buildUdp(
                        datagram.address.address,
                        session.clientIp,
                        datagram.port,
                        session.clientPort,
                        payload
                    )
                    try {
                        out.write(reply)
                        out.flush()
                    } catch (_: IOException) {
                        break
                    }
                }
            } catch (_: IOException) {
                // socket closed
            } finally {
                session.close()
                sessions.remove(session.key(), session)
            }
        }

        private inner class Session(
            val clientIp: ByteArray,
            val clientPort: Int,
            val serverHost: String,
            val serverPort: Int
        ) {
            val socket = DatagramSocket().apply { soTimeout = SESSION_IDLE_TIMEOUT_MS }
            val packet = DatagramPacket(ByteArray(2048), 2048)
            var receiverThread: Thread? = null

            @Volatile
            var closed = false

            fun key(): String = "${VpnIp.addrString(clientIp)}:$clientPort"

            fun close() {
                closed = true
                runCatching { socket.close() }
            }
        }
    }
}
