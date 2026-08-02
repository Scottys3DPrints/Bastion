package com.bastion.app.guard.vpn

import java.nio.ByteBuffer

/**
 * Just enough IPv4/UDP/DNS to read a question and answer it.
 *
 * Bastion is not a general packet filter and does not want to be: only the
 * synthetic resolver address is routed into the tunnel, so the only thing that
 * ever arrives here is a DNS query. Everything else on the device takes its
 * normal path and is never seen.
 */
object DnsPacket {

    const val PROTOCOL_UDP = 17
    const val DNS_PORT = 53

    data class Parsed(
        val ipHeaderLength: Int,
        val sourceIp: ByteArray,
        val destIp: ByteArray,
        val sourcePort: Int,
        val destPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** Returns null for anything that is not an IPv4 UDP datagram aimed at port 53. */
    fun parse(packet: ByteArray, length: Int): Parsed? {
        if (length < 28) return null
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val sourcePort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (destPort != DNS_PORT) return null

        val udpLength = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        val payloadLength = (udpLength - 8).coerceAtMost(length - ihl - 8)
        if (payloadLength <= 0) return null

        return Parsed(
            ipHeaderLength = ihl,
            sourceIp = packet.copyOfRange(12, 16),
            destIp = packet.copyOfRange(16, 20),
            sourcePort = sourcePort,
            destPort = destPort,
            payloadOffset = ihl + 8,
            payloadLength = payloadLength,
        )
    }

    /**
     * Reads the QNAME of the first question. Returns null on anything malformed
     * or compressed — a query Bastion cannot confidently read is one it lets
     * through rather than guesses about.
     */
    fun questionName(payload: ByteArray, offset: Int, length: Int): String? {
        if (length < 13) return null
        var cursor = offset + 12
        val end = offset + length
        val name = StringBuilder()

        while (cursor < end) {
            val len = payload[cursor].toInt() and 0xFF
            if (len == 0) break
            // Pointers should not appear in a question section.
            if (len and 0xC0 != 0) return null
            cursor++
            if (cursor + len > end) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(payload, cursor, len, Charsets.US_ASCII))
            cursor += len
            if (name.length > 253) return null
        }
        return name.takeIf { it.isNotEmpty() }?.toString()
    }

    /**
     * Turns a query into an NXDOMAIN response in place of a real answer.
     * NXDOMAIN rather than 0.0.0.0 so clients fail fast and cleanly.
     */
    fun buildNxDomain(query: ByteArray, offset: Int, length: Int): ByteArray {
        val response = query.copyOfRange(offset, offset + length)
        // QR=1, RD copied from the query.
        response[2] = ((response[2].toInt() and 0x01) or 0x80).toByte()
        // RA=1, RCODE=3 (name does not exist).
        response[3] = 0x83.toByte()
        // No answer, authority or additional records.
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        return response
    }


    /**
     * Rebuilds a query so it asks for [newHost] instead, keeping everything else.
     *
     * Only the question's name changes: the transaction id, the flags and the
     * type/class that follow the name are carried across untouched, so the
     * upstream resolver sees an ordinary query and the reply matches what was
     * sent.
     *
     * Returns null on anything it cannot read confidently — a name that is
     * compressed, a packet with no question, a host too long to encode. The
     * caller falls back to forwarding the original, because an unfiltered
     * search is a smaller failure than a search that does not resolve.
     */
    fun rewriteQuestion(
        packet: ByteArray,
        offset: Int,
        length: Int,
        newHost: String,
    ): ByteArray? {
        if (length < 13) return null
        val end = offset + length

        // Walk the existing name to find where it ends, so the four bytes of
        // QTYPE and QCLASS after it can be preserved.
        var cursor = offset + 12
        while (cursor < end) {
            val len = packet[cursor].toInt() and 0xFF
            if (len == 0) { cursor++; break }
            if (len and 0xC0 != 0) return null
            cursor += 1 + len
            if (cursor > end) return null
        }
        if (cursor + 4 > end) return null

        val encoded = encodeName(newHost) ?: return null
        val tail = packet.copyOfRange(cursor, end)
        val header = packet.copyOfRange(offset, offset + 12)

        return ByteArray(header.size + encoded.size + tail.size).also { out ->
            header.copyInto(out, 0)
            encoded.copyInto(out, header.size)
            tail.copyInto(out, header.size + encoded.size)
        }
    }

    /**
     * Serves an upstream reply under the name the client originally asked for.
     *
     * A client discards an answer whose question does not match what it sent,
     * so the reply for `forcedsafesearch.google.com` cannot simply be handed
     * back to something that asked about `google.com`. This takes the original
     * query, marks it as a response, and appends the upstream answer's records
     * with their name replaced by a pointer to the question — which is both
     * legal and how real resolvers encode it.
     *
     * Only A and AAAA records are carried over. Anything else in the reply
     * describes the safe host rather than the asked-for one, and copying it
     * would be asserting something untrue about a name.
     */
    fun reanswerUnderOriginalName(originalQuery: ByteArray, upstreamReply: ByteArray): ByteArray {
        val fallback = buildNxDomain(originalQuery, 0, originalQuery.size)
        if (upstreamReply.size < 12) return fallback

        val answerCount = ((upstreamReply[6].toInt() and 0xFF) shl 8) or
            (upstreamReply[7].toInt() and 0xFF)
        if (answerCount == 0) return fallback

        // Step past the reply's own question section.
        var cursor = 12
        val replyQuestions = ((upstreamReply[4].toInt() and 0xFF) shl 8) or
            (upstreamReply[5].toInt() and 0xFF)
        repeat(replyQuestions) {
            cursor = skipName(upstreamReply, cursor) ?: return fallback
            cursor += 4
            if (cursor > upstreamReply.size) return fallback
        }

        val records = ArrayList<ByteArray>()
        repeat(answerCount) {
            cursor = skipName(upstreamReply, cursor) ?: return@repeat
            if (cursor + 10 > upstreamReply.size) return@repeat
            val type = ((upstreamReply[cursor].toInt() and 0xFF) shl 8) or
                (upstreamReply[cursor + 1].toInt() and 0xFF)
            val dataLength = ((upstreamReply[cursor + 8].toInt() and 0xFF) shl 8) or
                (upstreamReply[cursor + 9].toInt() and 0xFF)
            val bodyStart = cursor
            cursor += 10 + dataLength
            if (cursor > upstreamReply.size) return@repeat
            if (type != TYPE_A && type != TYPE_AAAA) return@repeat

            // 0xC00C is a pointer to offset 12 — the question's name.
            val record = ByteArray(2 + (cursor - bodyStart))
            record[0] = 0xC0.toByte()
            record[1] = 0x0C
            upstreamReply.copyInto(record, 2, bodyStart, cursor)
            records.add(record)
        }
        if (records.isEmpty()) return fallback

        val response = originalQuery.copyOf()
        response[2] = ((response[2].toInt() and 0x01) or 0x80).toByte()
        response[3] = 0x80.toByte()          // RA=1, RCODE=0
        response[6] = (records.size shr 8).toByte()
        response[7] = records.size.toByte()
        response[8] = 0; response[9] = 0     // no authority records
        response[10] = 0; response[11] = 0   // no additional records

        val total = response.size + records.sumOf { it.size }
        return ByteArray(total).also { out ->
            response.copyInto(out, 0)
            var at = response.size
            records.forEach { it.copyInto(out, at); at += it.size }
        }
    }

    /** Encodes "a.b.com" as length-prefixed labels ending in a zero byte. */
    private fun encodeName(host: String): ByteArray? {
        val labels = host.trimEnd('.').split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        val size = labels.sumOf { it.length + 1 } + 1
        if (size > 255) return null

        return ByteArray(size).also { out ->
            var at = 0
            labels.forEach { label ->
                out[at++] = label.length.toByte()
                label.toByteArray(Charsets.US_ASCII).copyInto(out, at)
                at += label.length
            }
            out[at] = 0
        }
    }

    /** Returns the offset just past a name, following one level of pointer. */
    private fun skipName(packet: ByteArray, start: Int): Int? {
        var cursor = start
        while (cursor < packet.size) {
            val len = packet[cursor].toInt() and 0xFF
            if (len == 0) return cursor + 1
            if (len and 0xC0 == 0xC0) return cursor + 2
            cursor += 1 + len
        }
        return null
    }

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    /** Wraps a DNS reply in an IPv4/UDP packet flowing back to the asking app. */
    fun buildResponsePacket(request: Parsed, dnsPayload: ByteArray): ByteArray {
        val totalLength = 20 + 8 + dnsPayload.size
        val buffer = ByteBuffer.allocate(totalLength)

        buffer.put(0x45)                       // IPv4, 5-word header
        buffer.put(0)                          // DSCP / ECN
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0)                     // identification
        buffer.putShort(0x4000.toShort())      // don't fragment
        buffer.put(64)                         // TTL
        buffer.put(PROTOCOL_UDP.toByte())
        buffer.putShort(0)                     // checksum placeholder
        buffer.put(request.destIp)             // swap: we answer as the resolver
        buffer.put(request.sourceIp)

        val header = buffer.array()
        val checksum = ipChecksum(header, 0, 20)
        header[10] = (checksum shr 8).toByte()
        header[11] = checksum.toByte()
        buffer.position(20)

        buffer.putShort(request.destPort.toShort())
        buffer.putShort(request.sourcePort.toShort())
        buffer.putShort((8 + dnsPayload.size).toShort())
        // UDP checksums are optional over IPv4; zero means "not computed", which
        // every stack accepts and saves recomputing a pseudo-header per query.
        buffer.putShort(0)
        buffer.put(dnsPayload)

        return buffer.array()
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
