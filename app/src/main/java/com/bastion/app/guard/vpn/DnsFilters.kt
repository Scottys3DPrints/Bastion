package com.bastion.app.guard.vpn

import android.content.Context
import android.provider.Settings

/**
 * The two DNS-level filters, and the fact that they cannot both run.
 *
 * Bastion's content filter is a VpnService, and a VPN that declares a DNS
 * server takes over name resolution for the device — Bastion points it at a
 * synthetic resolver at 10.111.222.3 that speaks plain DNS inside the tunnel.
 * Android's Private DNS, set to a hostname, is strict DNS-over-TLS: it must
 * open a TLS connection to that hostname on port 853 and will refuse to resolve
 * anything at all if it cannot. With Bastion's tunnel holding the resolver,
 * that connection never happens, and Android does the honest thing and reports
 * the truth:
 *
 *     Network has no internet access
 *     Private DNS server cannot be accessed
 *
 * The app was presenting these as two complementary layers and counting them
 * as two separate points of Guard strength, which is worse than useless: it
 * encouraged turning both on, and turning both on takes the phone off the
 * internet entirely.
 *
 * They do the same job. Private DNS is the stronger of the two — it works below
 * the app layer, survives Bastion being killed, and cannot be undone by
 * disabling an accessibility service — but it is a system setting, so Bastion
 * can neither switch it on nor stop it being switched off. Bastion's own filter
 * is weaker and enforceable. Pick one; this file exists so the app can say so.
 */
object DnsFilters {

    /** The hostname Android is set to use, or null when Private DNS is off. */
    fun privateDnsHostname(context: Context): String? = runCatching {
        val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
        // "opportunistic" is the default and filters nothing; only "hostname"
        // means a specific resolver was chosen.
        if (mode != "hostname") return null
        Settings.Global.getString(context.contentResolver, "private_dns_specifier")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun privateDnsIsSet(context: Context): Boolean = privateDnsHostname(context) != null

    /**
     * True when both are on, which means the phone currently has no working
     * name resolution at all.
     */
    fun bothRunning(context: Context, bastionFilterOn: Boolean): Boolean =
        bastionFilterOn && privateDnsIsSet(context)

    /**
     * Resolvers that actually block adult content, as opposed to merely being
     * set.
     *
     * This distinction was a one-tap way out of the whole filter, and it did not
     * look like one. The conflict card offers "keep Private DNS, drop Bastion's
     * filter" whenever both are running, on the reasoning that Private DNS is
     * the stronger of the two — but "running" only ever meant *a* hostname was
     * present. Point Private DNS at dns.google, which filters nothing, and the
     * card appears, and one tap disables Bastion's filter with no wait and no
     * partner code. The man ends up with no filtering at all, having pressed a
     * button that said it was keeping the better one.
     *
     * Deliberately a short list of resolvers whose whole purpose is this, rather
     * than a guess. Anything unrecognised is treated as "not known to filter",
     * which is the honest reading — including NextDNS and personal AdGuard
     * profiles, whose blocking depends on a configuration Bastion cannot see.
     */
    private val FILTERING_RESOLVERS = setOf(
        "family.cloudflare-dns.com",
        "familyshield.opendns.com",
        "family-filter-dns.cleanbrowsing.org",
        "adult-filter-dns.cleanbrowsing.org",
        "family.adguard-dns.com",
        "dns-family.adguard.com",
    )

    /** Whether the resolver Android is set to is one that blocks adult content. */
    fun privateDnsFilters(context: Context): Boolean =
        privateDnsHostname(context)?.lowercase()?.trim() in FILTERING_RESOLVERS
}
