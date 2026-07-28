package com.bastion.app

import com.bastion.app.data.content.Blocklist
import com.bastion.app.guard.vpn.DomainFilter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainFilterTest {

    private val filter = DomainFilter(
        blocked = setOf("pornhub.com", "example-adult.com"),
        allowed = setOf("sussex.ac.uk", "nhs.uk"),
        keywords = listOf("porn", "hentai"),
    )

    @Test
    fun `blocks an exact domain`() {
        assertTrue(filter.isBlocked("pornhub.com"))
    }

    @Test
    fun `blocks subdomains without needing their own entry`() {
        assertTrue(filter.isBlocked("cdn.media.pornhub.com"))
        assertTrue(filter.isBlocked("www.example-adult.com"))
    }

    @Test
    fun `leaves unrelated domains alone`() {
        assertFalse(filter.isBlocked("github.com"))
        assertFalse(filter.isBlocked("bbc.co.uk"))
    }

    @Test
    fun `the allow-list beats the keyword heuristic`() {
        // "sussex" contains no blocked keyword, but this is the class of false
        // positive the allow-list exists to guarantee against.
        assertFalse(filter.isBlocked("www.sussex.ac.uk"))
        assertFalse(filter.isBlocked("111.nhs.uk"))
    }

    @Test
    fun `keyword heuristic catches the long tail`() {
        assertTrue(filter.isBlocked("some-random-porn-site.net"))
        assertTrue(filter.isBlocked("hentai-mirror.xyz"))
    }

    @Test
    fun `trailing dots and www prefixes are normalised`() {
        assertTrue(filter.isBlocked("PornHub.com."))
        assertTrue(filter.isBlocked("WWW.PORNHUB.COM"))
    }

    @Test
    fun `an empty hostname is never blocked`() {
        assertFalse(filter.isBlocked(""))
    }

    /**
     * Guards the shipped list against the Scunthorpe problem: health, education
     * and recovery resources must survive the real keyword set, because blocking
     * them would do more harm than the filter prevents.
     */
    @Test
    fun `shipped blocklist does not catch health education or recovery sites`() {
        val json = Json { ignoreUnknownKeys = true }
        val list = json.decodeFromString<Blocklist>(
            File("src/main/assets/content/blocklist.json").readText()
        )
        val real = DomainFilter(list.domains.toSet(), list.allow.toSet(), list.keywords)

        listOf(
            "www.nhs.uk",
            "www.sussex.ac.uk",
            "essex.gov.uk",
            "plannedparenthood.org",
            "healthline.com",
            "findahelpline.com",
            "samaritans.org",
            "988lifeline.org",
            "rainn.org",
            "psychologytoday.com",
            "en.wikipedia.org",
            "biblegateway.com",
            "fightthenewdrug.org",
            "analytics.google.com",
        ).forEach { host ->
            assertFalse("$host must never be blocked", real.isBlocked(host))
        }
    }

    @Test
    fun `shipped blocklist still blocks what it is for`() {
        val json = Json { ignoreUnknownKeys = true }
        val list = json.decodeFromString<Blocklist>(
            File("src/main/assets/content/blocklist.json").readText()
        )
        val real = DomainFilter(list.domains.toSet(), list.allow.toSet(), list.keywords)

        listOf("pornhub.com", "m.xvideos.com", "nhentai.net", "chaturbate.com")
            .forEach { host -> assertTrue("$host should be blocked", real.isBlocked(host)) }
    }
}
