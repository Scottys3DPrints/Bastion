package com.bastion.app.guard.vpn

/**
 * Forced SafeSearch, done in the resolver.
 *
 * A blocklist can only block a domain, and the everyday bypass is not a
 * blocked domain — it is image and video search on domains that have to stay
 * allowed. `google.com` and `youtube.com` cannot be blocked without breaking
 * the phone, so explicit results arrive through a door the filter is holding
 * open on purpose.
 *
 * The search engines publish alternative hostnames that serve the same service
 * with explicit results stripped out, and a resolver can quietly answer with
 * those instead. It is the technique school and family filters have used for
 * years, and it works for every app and browser on the device rather than only
 * the one that happens to obey a policy.
 *
 * The mapping is host-to-host rather than host-to-address on purpose: the
 * addresses behind these names change, so the safe name is resolved upstream at
 * query time and its answer handed back under the name that was asked for.
 */
internal object SafeSearch {

    /**
     * Query name → the name whose answer should be returned instead.
     *
     * Matched on the exact host and on any subdomain of it, so `images.
     * google.com` and `www.google.co.uk` are covered along with the bare
     * domain.
     */
    private val REDIRECTS: List<Pair<String, String>> = listOf(
        // Google, including the country domains, which are the obvious way
        // round a rule that only names google.com.
        "google.com" to "forcedsafesearch.google.com",
        "google.co.uk" to "forcedsafesearch.google.com",
        "google.de" to "forcedsafesearch.google.com",
        "google.ca" to "forcedsafesearch.google.com",
        "google.com.au" to "forcedsafesearch.google.com",
        "google.ie" to "forcedsafesearch.google.com",
        "google.co.in" to "forcedsafesearch.google.com",
        "google.fr" to "forcedsafesearch.google.com",
        "google.es" to "forcedsafesearch.google.com",
        "google.it" to "forcedsafesearch.google.com",
        "google.nl" to "forcedsafesearch.google.com",

        "youtube.com" to "restrict.youtube.com",
        "youtubei.googleapis.com" to "restrict.youtube.com",
        "youtube.googleapis.com" to "restrict.youtube.com",
        "m.youtube.com" to "restrict.youtube.com",

        "bing.com" to "strict.bing.com",
        "duckduckgo.com" to "safe.duckduckgo.com",
    )

    /**
     * The name to resolve in place of [hostname], or null to leave it alone.
     *
     * Returns null when the query is already for the safe host, which matters:
     * without it the lookup Bastion makes to find the safe address would map
     * onto itself forever.
     */
    fun redirectFor(hostname: String): String? {
        val host = hostname.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return null
        if (REDIRECTS.any { host == it.second || host.endsWith("." + it.second) }) return null

        return REDIRECTS.firstOrNull { (from, _) ->
            host == from || host.endsWith(".$from")
        }?.second
    }
}
