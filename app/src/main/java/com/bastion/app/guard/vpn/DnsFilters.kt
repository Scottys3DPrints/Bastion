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
}
