package com.bastion.app

import com.bastion.app.guard.vpn.DnsPacket
import com.bastion.app.guard.vpn.SafeSearch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forced SafeSearch rewrites DNS on the wire, which is the highest-risk thing
 * in this app: get it wrong and the phone stops resolving names at all, which
 * is indistinguishable from "no internet" and teaches people to turn the whole
 * filter off. Every failure path here has to fall back rather than produce a
 * packet a client will reject.
 */
class SafeSearchTest {

    // --- which names get redirected ---------------------------------------

    @Test
    fun `search engines are redirected to their safe hosts`() {
        assertEquals("forcedsafesearch.google.com", SafeSearch.redirectFor("google.com"))
        assertEquals("restrict.youtube.com", SafeSearch.redirectFor("youtube.com"))
        assertEquals("strict.bing.com", SafeSearch.redirectFor("bing.com"))
        assertEquals("safe.duckduckgo.com", SafeSearch.redirectFor("duckduckgo.com"))
    }

    @Test
    fun `subdomains are covered too`() {
        assertEquals("forcedsafesearch.google.com", SafeSearch.redirectFor("www.google.com"))
        assertEquals("forcedsafesearch.google.com", SafeSearch.redirectFor("images.google.com"))
        assertEquals("restrict.youtube.com", SafeSearch.redirectFor("m.youtube.com"))
    }

    /** A rule naming only google.com is one hop from useless. */
    @Test
    fun `country domains are covered`() {
        listOf("google.co.uk", "google.de", "google.fr", "google.com.au").forEach {
            assertEquals("$it should be redirected", "forcedsafesearch.google.com", SafeSearch.redirectFor(it))
        }
    }

    @Test
    fun `case and trailing dots do not matter`() {
        assertEquals("forcedsafesearch.google.com", SafeSearch.redirectFor("WWW.Google.COM."))
    }

    /**
     * The loop this prevents: Bastion looks up the safe host, that lookup maps
     * onto itself, and the query never reaches a resolver.
     */
    @Test
    fun `the safe hosts are not themselves redirected`() {
        assertNull(SafeSearch.redirectFor("forcedsafesearch.google.com"))
        assertNull(SafeSearch.redirectFor("restrict.youtube.com"))
        assertNull(SafeSearch.redirectFor("safe.duckduckgo.com"))
    }

    @Test
    fun `unrelated names are left alone`() {
        assertNull(SafeSearch.redirectFor("wikipedia.org"))
        assertNull(SafeSearch.redirectFor("example.com"))
        // The suffix match must not fire on a lookalike domain.
        assertNull(SafeSearch.redirectFor("notgoogle.com"))
        assertNull(SafeSearch.redirectFor("google.com.evil.example"))
    }

    // --- rewriting the question -------------------------------------------

    private fun query(host: String, id: Int = 0x1234): ByteArray {
        val labels = host.split('.')
        val out = ArrayList<Byte>()
        out.add((id shr 8).toByte()); out.add(id.toByte())
        out.add(0x01); out.add(0x00)   // standard query, recursion desired
        out.add(0x00); out.add(0x01)   // one question
        repeat(6) { out.add(0x00) }    // no answers, authority, additional
        labels.forEach { label ->
            out.add(label.length.toByte())
            label.toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        }
        out.add(0x00)
        out.add(0x00); out.add(0x01)   // QTYPE A
        out.add(0x00); out.add(0x01)   // QCLASS IN
        return out.toByteArray()
    }

    @Test
    fun `the rewritten query asks for the safe host`() {
        val original = query("www.google.com")
        val rewritten = DnsPacket.rewriteQuestion(
            original, 0, original.size, "forcedsafesearch.google.com",
        )
        assertNotNull(rewritten)
        assertEquals(
            "forcedsafesearch.google.com",
            DnsPacket.questionName(rewritten!!, 0, rewritten.size),
        )
    }

    /** The reply is matched to the request by id; losing it loses the answer. */
    @Test
    fun `the rewritten query keeps the transaction id and flags`() {
        val original = query("www.google.com", id = 0xABCD)
        val rewritten = DnsPacket.rewriteQuestion(
            original, 0, original.size, "forcedsafesearch.google.com",
        )!!
        assertArrayEquals(original.copyOfRange(0, 12), rewritten.copyOfRange(0, 12))
    }

    @Test
    fun `the rewritten query keeps the type and class`() {
        val original = query("www.google.com")
        val rewritten = DnsPacket.rewriteQuestion(
            original, 0, original.size, "forcedsafesearch.google.com",
        )!!
        // The last four bytes are QTYPE and QCLASS.
        assertArrayEquals(
            original.copyOfRange(original.size - 4, original.size),
            rewritten.copyOfRange(rewritten.size - 4, rewritten.size),
        )
    }

    @Test
    fun `a malformed query is refused rather than guessed at`() {
        assertNull(DnsPacket.rewriteQuestion(ByteArray(4), 0, 4, "safe.example.com"))
    }

    @Test
    fun `an unencodable host is refused`() {
        val original = query("www.google.com")
        val tooLong = "a".repeat(64) + ".example.com"
        assertNull(DnsPacket.rewriteQuestion(original, 0, original.size, tooLong))
    }

    // --- serving the answer back under the original name -------------------

    /** Builds a reply for [host] carrying one A record. */
    private fun replyWithA(host: String, address: ByteArray): ByteArray {
        val q = query(host)
        val out = q.copyOf().toMutableList()
        out[2] = 0x81.toByte(); out[3] = 0x80.toByte()   // response, no error
        out[6] = 0x00; out[7] = 0x01                     // one answer
        // Answer: pointer to the question name, type A, class IN, TTL, 4 bytes.
        listOf(0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x01, 0x2C, 0x00, 0x04)
            .forEach { out.add(it.toByte()) }
        address.forEach { out.add(it) }
        return out.toByteArray()
    }

    @Test
    fun `the answer comes back under the name that was asked for`() {
        val original = query("www.google.com")
        val upstream = replyWithA("forcedsafesearch.google.com", byteArrayOf(1, 2, 3, 4))

        val served = DnsPacket.reanswerUnderOriginalName(original, upstream)

        assertEquals(
            "the client discards anything that does not match its question",
            "www.google.com",
            DnsPacket.questionName(served, 0, served.size),
        )
        // Flagged as a response, one answer, no error.
        assertTrue("QR bit not set", served[2].toInt() and 0x80 != 0)
        assertEquals("RCODE must be 0", 0, served[3].toInt() and 0x0F)
        assertEquals(1, ((served[6].toInt() and 0xFF) shl 8) or (served[7].toInt() and 0xFF))
        // The safe host's address survives the transplant.
        assertTrue(
            "address missing from the answer",
            served.toList().windowed(4).any { it == listOf<Byte>(1, 2, 3, 4) },
        )
    }

    @Test
    fun `a reply with no answers becomes a clean failure`() {
        val original = query("www.google.com")
        val empty = query("forcedsafesearch.google.com").copyOf().also {
            it[2] = 0x81.toByte(); it[3] = 0x80.toByte()
        }
        val served = DnsPacket.reanswerUnderOriginalName(original, empty)
        // NXDOMAIN: fail fast rather than hand back something malformed.
        assertEquals(3, served[3].toInt() and 0x0F)
    }

    @Test
    fun `a truncated reply does not crash or produce nonsense`() {
        val original = query("www.google.com")
        val served = DnsPacket.reanswerUnderOriginalName(original, ByteArray(3))
        assertEquals(3, served[3].toInt() and 0x0F)
    }
}
