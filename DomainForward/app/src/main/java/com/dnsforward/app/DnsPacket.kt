package com.dnsforward.app

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Minimal DNS (RFC 1035) helpers: parse the first question of a query and
 * synthesize an answer that points a domain at a chosen IP.
 */
object DnsPacket {

    const val TTL = 60

    data class Question(val name: String, val type: Int, val qclass: Int)

    private fun u16(d: ByteArray, i: Int): Int =
        ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    private fun putU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v ushr 8)
        out.write(v and 0xFF)
    }

    private fun putU32(out: ByteArrayOutputStream, v: Int) {
        out.write(v ushr 24)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /**
     * Decodes a DNS name that starts at [start] (labels, possibly ending in a
     * compression pointer). Returns the lowercased name plus the offset just
     * past the name inside the original packet, or null when malformed.
     */
    private fun readName(pkt: ByteArray, start: Int): Pair<String, Int>? {
        var pos = start
        var end = -1
        var jumped = false
        val parts = ArrayList<String>()
        var hops = 0
        while (true) {
            if (++hops > 40 || pos < 0 || pos >= pkt.size) return null
            val b = pkt[pos].toInt() and 0xFF
            when {
                b == 0 -> {
                    if (end < 0) end = pos + 1
                    return parts.joinToString(".").lowercase() to end
                }
                b and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= pkt.size) return null
                    if (end < 0) end = pos + 2
                    if (jumped) return null
                    jumped = true
                    val target = ((b and 0x3F) shl 8) or (pkt[pos + 1].toInt() and 0xFF)
                    if (target >= pkt.size) return null
                    pos = target
                }
                else -> {
                    if (pos + 1 + b > pkt.size) return null
                    val sb = StringBuilder(b)
                    for (i in 0 until b) {
                        sb.append((pkt[pos + 1 + i].toInt() and 0xFF).toChar())
                    }
                    parts.add(sb.toString())
                    pos += 1 + b
                }
            }
        }
    }

    /** Parses the first question of a DNS query packet. */
    fun parseQuery(pkt: ByteArray): Question? {
        if (pkt.size < 12) return null
        if (u16(pkt, 4) < 1) return null
        val (name, nameEnd) = readName(pkt, 12) ?: return null
        if (nameEnd + 4 > pkt.size) return null
        val type = u16(pkt, nameEnd)
        val qclass = u16(pkt, nameEnd + 2)
        return Question(name.trimEnd('.'), type, qclass)
    }

    /**
     * Builds a DNS response that answers [q] with [ip].
     * Returns null when the IP family is unsupported.
     */
    fun buildAnswer(query: ByteArray, q: Question, ip: InetAddress): ByteArray? {
        val rtype = when (ip) {
            is Inet4Address -> 1 // A
            is Inet6Address -> 28 // AAAA
            else -> return null
        }
        val hasAnswer = q.type == rtype
        val out = ByteArrayOutputStream(512)
        // transaction id
        out.write(query[0].toInt())
        out.write(query[1].toInt())
        // flags: QR + copy RD + RA
        val flags = 0x8180 or (u16(query, 2) and 0x0100)
        putU16(out, flags)
        putU16(out, 1) // QDCOUNT
        putU16(out, if (hasAnswer) 1 else 0) // ANCOUNT
        putU16(out, 0) // NSCOUNT
        putU16(out, 0) // ARCOUNT
        writeName(out, q.name)
        putU16(out, q.type)
        putU16(out, q.qclass)
        if (hasAnswer) {
            putU16(out, 0xC00C) // name pointer back to the question
            putU16(out, rtype)
            putU16(out, 1) // class IN
            putU32(out, TTL)
            val data = ip.address
            putU16(out, data.size)
            out.write(data)
        }
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            out.write(label.length.coerceAtMost(63))
            for (ch in label) out.write(ch.code)
        }
        out.write(0)
    }
}
