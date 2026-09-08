package com.dnsforward.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * Helpers to parse and craft IPv4/UDP packets exchanged with the VPN tunnel.
 */
object VpnIp {

    class UdpPacket(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    private val ipId = AtomicInteger(0x1234)

    private fun u16(d: ByteArray, i: Int): Int =
        ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    fun addrString(ip: ByteArray): String =
        ip.joinToString(".") { (it.toInt() and 0xFF).toString() }

    /** Parses one IPv4+UDP datagram. Returns null when the packet is not a full UDP/IPv4 packet. */
    fun parseUdp(buf: ByteArray, n: Int): UdpPacket? {
        if (n < 28) return null
        val vihl = buf[0].toInt() and 0xFF
        if ((vihl ushr 4) != 4) return null
        val ihl = (vihl and 0x0F) * 4
        if (ihl < 20 || n < ihl + 8) return null
        if ((buf[9].toInt() and 0xFF) != 17) return null // UDP
        val total = u16(buf, 2)
        val len = if (total in 28..n) total else n
        val srcIp = buf.copyOfRange(12, 16)
        val dstIp = buf.copyOfRange(16, 20)
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        val udpLen = u16(buf, ihl + 4)
        val dataStart = ihl + 8
        val dataLen = when {
            udpLen >= 8 && dataStart + (udpLen - 8) <= len -> udpLen - 8
            else -> len - dataStart
        }.coerceAtLeast(0)
        val payload = buf.copyOfRange(dataStart, dataStart + dataLen)
        return UdpPacket(srcIp, dstIp, srcPort, dstPort, payload)
    }

    /** Builds an IPv4+UDP packet from [srcIp]:[srcPort] to [dstIp]:[dstPort] carrying [payload]. */
    fun buildUdp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45.toByte()
        b[2] = ((total ushr 8) and 0xFF).toByte()
        b[3] = (total and 0xFF).toByte()
        val id = ipId.getAndIncrement() and 0xFFFF
        b[4] = ((id ushr 8) and 0xFF).toByte()
        b[5] = (id and 0xFF).toByte()
        b[8] = 64.toByte()
        b[9] = 17.toByte()
        System.arraycopy(srcIp, 0, b, 12, 4)
        System.arraycopy(dstIp, 0, b, 16, 4)
        val sum = checksum(b, 20)
        b[10] = ((sum ushr 8) and 0xFF).toByte()
        b[11] = (sum and 0xFF).toByte()
        val u = 20
        b[u] = ((srcPort ushr 8) and 0xFF).toByte()
        b[u + 1] = (srcPort and 0xFF).toByte()
        b[u + 2] = ((dstPort ushr 8) and 0xFF).toByte()
        b[u + 3] = (dstPort and 0xFF).toByte()
        val ulen = 8 + payload.size
        b[u + 4] = ((ulen ushr 8) and 0xFF).toByte()
        b[u + 5] = (ulen and 0xFF).toByte()
        b[u + 6] = 0
        b[u + 7] = 0
        System.arraycopy(payload, 0, b, u + 8, payload.size)
        return b
    }

    private fun checksum(data: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < len) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}
