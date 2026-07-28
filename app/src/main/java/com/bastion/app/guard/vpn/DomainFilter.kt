package com.bastion.app.guard.vpn

/**
 * Decides whether a hostname should resolve.
 *
 * Three layers, checked in this order:
 *   1. the allow-list, which always wins — over-blocking is not a neutral
 *      failure here. Blocking sexual-health information, a university in Essex
 *      or a recovery helpline would do real harm, so those are protected first;
 *   2. the explicit blocklist, matched on the domain and every parent, so one
 *      entry covers every subdomain;
 *   3. a conservative keyword heuristic for the long tail. Bare "sex" is
 *      deliberately absent — only compound forms appear, for the same reason.
 */
class DomainFilter(
    blocked: Set<String>,
    allowed: Set<String>,
    private val keywords: List<String>,
) {

    private val blocked: Set<String> = blocked.map { it.normalise() }.toSet()
    private val allowed: Set<String> = allowed.map { it.normalise() }.toSet()

    fun isBlocked(hostname: String): Boolean {
        val host = hostname.normalise()
        if (host.isEmpty()) return false

        val labels = host.split('.')
        // Walk from the full host up through each parent domain.
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in allowed) return false
            if (candidate in blocked) return true
        }
        return keywords.any { host.contains(it) }
    }

    private fun String.normalise(): String =
        trim().lowercase().removeSuffix(".").removePrefix("www.")

    companion object {
        val PERMISSIVE = DomainFilter(emptySet(), emptySet(), emptyList())
    }
}
