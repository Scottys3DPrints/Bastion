package com.bastion.app.data.repo

import com.bastion.app.data.content.ContentRepository
import com.bastion.app.data.db.AllowedDomainEntity
import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.BlockedDomainEntity
import com.bastion.app.data.db.ChangeStatus
import com.bastion.app.data.db.FeedRuleEntity
import com.bastion.app.data.db.GuardChangeRequestEntity
import com.bastion.app.data.db.GuardDao
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.MatchType
import com.bastion.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class GuardRepository(
    private val guardDao: GuardDao,
    private val content: ContentRepository,
    private val settings: SettingsStore,
) {

    val guardedApps: Flow<List<GuardedAppEntity>> = guardDao.guardedApps()
    val feedRules: Flow<List<FeedRuleEntity>> = guardDao.feedRules()
    val userDomains: Flow<List<BlockedDomainEntity>> = guardDao.userDomains()
    val pendingChanges: Flow<List<GuardChangeRequestEntity>> = guardDao.pendingChanges()

    suspend fun enabledGuardedApps() = guardDao.enabledGuardedApps()
    suspend fun enabledFeedRules() = guardDao.enabledFeedRules()

    suspend fun upsertApp(app: GuardedAppEntity) = guardDao.upsertApp(app)
    suspend fun removeApp(packageName: String) = guardDao.removeApp(packageName)
    suspend fun upsertRule(rule: FeedRuleEntity) = guardDao.upsertRule(rule)
    suspend fun deleteRule(id: String) = guardDao.deleteRule(id)

    suspend fun addUserDomain(domain: String) {
        guardDao.upsertDomain(BlockedDomainEntity(domain.normaliseDomain(), userAdded = true))
        invalidateFilterCache()
    }

    suspend fun removeUserDomain(domain: String) {
        guardDao.removeDomain(domain)
        invalidateFilterCache()
    }

    /**
     * The filter's working set: blocked suffixes, allow-list and keywords.
     *
     * Cached because this is two full table scans over ~200 rows and it sits on
     * the path the VPN and the browser both take. Invalidated explicitly on
     * every domain write below, so a newly blocked site takes effect at once —
     * a stale filter here would be a silent hole in the wall.
     */
    suspend fun filterData(): FilterData {
        cachedFilterData?.let { return it }
        return FilterData(
            blocked = guardDao.enabledDomains().map { it.domain }.toSet(),
            allowed = guardDao.allowedDomains().map { it.domain }.toSet(),
            keywords = content.blocklist().keywords,
        ).also { cachedFilterData = it }
    }

    @Volatile
    private var cachedFilterData: FilterData? = null

    private fun invalidateFilterCache() {
        cachedFilterData = null
    }

    data class FilterData(
        val blocked: Set<String>,
        val allowed: Set<String>,
        val keywords: List<String>,
    )

    /**
     * Seeds the bundled blocklist and the starter feed rules on first run.
     * Idempotent: re-running never clobbers a rule the user has edited, because
     * built-in rows are only inserted when the table is empty.
     */
    suspend fun seedIfEmpty() {
        // Guarded by a flag rather than by row counts. Keyed on emptiness, a
        // user who deliberately cleared his blocklist got the whole thing back
        // on next launch, silently undoing a decision he had made.
        if (settings.current().guardSeeded) return

        if (guardDao.domainCount() == 0) {
            val list = content.blocklist()
            guardDao.upsertDomains(list.domains.map { BlockedDomainEntity(it.normaliseDomain()) })
            guardDao.upsertAllowed(list.allow.map { AllowedDomainEntity(it.normaliseDomain()) })
            invalidateFilterCache()
        }
        if (guardDao.feedRuleCount() == 0) {
            guardDao.upsertRules(builtInFeedRules())
        }
        settings.setGuardSeeded(true)
    }

    // --- Cooling-off lock -------------------------------------------------
    //
    // Loosening a guard never takes effect immediately. The request matures in
    // the background; the man in a weak moment at 1am has to still want it at
    // 3am. Tightening a guard, by contrast, is always instant.

    suspend fun requestWeakening(description: String, payload: String): GuardChangeRequestEntity {
        val hours = settings.current().coolingOffHours
        val now = System.currentTimeMillis()
        val request = GuardChangeRequestEntity(
            id = UUID.randomUUID().toString(),
            requestedAt = now,
            effectiveAt = now + hours * 60L * 60L * 1000L,
            description = description,
            payload = payload,
        )
        guardDao.upsertChangeRequest(request)
        return request
    }

    suspend fun cancelChange(id: String) = guardDao.setChangeStatus(id, ChangeStatus.CANCELLED)

    suspend fun maturedChanges() = guardDao.maturedChanges(System.currentTimeMillis())

    suspend fun markApplied(id: String) = guardDao.setChangeStatus(id, ChangeStatus.APPLIED)

    /**
     * Carries out every request whose delay has run out.
     *
     * This is the other half of the cooling-off lock, and it was missing: the
     * delay was enforced, the request was marked applied when it matured, and
     * the change itself never happened. A man could ask to unblock something,
     * wait the full twenty-four hours, and find it still blocked with nothing to
     * show for the wait. Fail-safe, but broken — the lock has to open as
     * reliably as it holds, or it stops being trusted.
     *
     * @return true if the content filter should now be stopped, which needs a
     * Context the repository deliberately does not hold.
     */
    suspend fun applyMaturedChanges(): Boolean {
        var stopFilter = false

        maturedChanges().forEach { change ->
            val parts = change.payload.split(':')
            when (parts.firstOrNull()) {
                "app" -> {
                    val pkg = parts.getOrNull(1)
                    val mode = parts.getOrNull(2)?.let { runCatching { BlockMode.valueOf(it) }.getOrNull() }
                    if (pkg != null && mode != null) {
                        guardDao.guardedApps().first().firstOrNull { it.packageName == pkg }?.let {
                            guardDao.upsertApp(it.copy(mode = mode, updatedAt = System.currentTimeMillis()))
                        }
                    }
                }
                "remove" -> parts.getOrNull(1)?.let { guardDao.removeApp(it) }
                "rule" -> {
                    val id = parts.getOrNull(1)
                    if (id != null) {
                        guardDao.feedRules().first().firstOrNull { it.id == id }?.let {
                            guardDao.upsertRule(it.copy(enabled = false, updatedAt = System.currentTimeMillis()))
                        }
                    }
                }
                "domain" -> parts.getOrNull(1)?.let {
                    guardDao.removeDomain(it)
                    invalidateFilterCache()
                }
                "cooloff" -> parts.getOrNull(1)?.toIntOrNull()?.let { settings.setCoolingOffHours(it) }
                "vpn" -> {
                    settings.setVpnEnabled(false)
                    stopFilter = true
                }
            }
            markApplied(change.id)
        }
        return stopFilter
    }

    private fun String.normaliseDomain(): String =
        trim().lowercase().removePrefix("http://").removePrefix("https://")
            .removePrefix("www.").substringBefore('/').substringBefore(':')

    companion object {

        /**
         * Starter rules for the short-form feeds.
         *
         * These are best-effort: the view ids belong to other companies' apps and
         * change without notice when those apps are redesigned. That is exactly
         * why rules live in the database and why Learn Mode exists — when a rule
         * stops firing, the user recaptures it in seconds instead of waiting for
         * a new build.
         */
        fun builtInFeedRules(): List<FeedRuleEntity> = listOf(
            // Instagram Reels
            rule("com.instagram.android", "Instagram Reels", MatchType.VIEW_ID, "clips_viewer"),
            rule("com.instagram.android", "Instagram Reels (viewer)", MatchType.VIEW_ID, "reel_viewer"),
            rule("com.instagram.android", "Instagram Reels (tab)", MatchType.CONTENT_DESC, "Reels"),

            // YouTube Shorts
            rule("com.google.android.youtube", "YouTube Shorts", MatchType.VIEW_ID, "reel_recycler"),
            rule("com.google.android.youtube", "YouTube Shorts (player)", MatchType.VIEW_ID, "reel_player_page_container"),
            rule("com.google.android.youtube", "YouTube Shorts (root)", MatchType.VIEW_ID, "reel_watch_fragment_root"),
            rule("com.google.android.youtube", "YouTube Shorts (tab)", MatchType.CONTENT_DESC, "Shorts"),

            // TikTok — both the global and the regional package names
            rule("com.zhiliaoapp.musically", "TikTok For You", MatchType.VIEW_ID, "feed_tab_view"),
            rule("com.zhiliaoapp.musically", "TikTok For You (pager)", MatchType.VIEW_ID, "viewpager_container"),
            rule("com.ss.android.ugc.trill", "TikTok For You", MatchType.VIEW_ID, "feed_tab_view"),

            // Facebook Reels
            rule("com.facebook.katana", "Facebook Reels", MatchType.VIEW_ID, "video_home"),
            rule("com.facebook.katana", "Facebook Reels (tab)", MatchType.CONTENT_DESC, "Reels"),

            // Snapchat Spotlight
            rule("com.snapchat.android", "Snapchat Spotlight", MatchType.VIEW_ID, "spotlight"),

            // X / Twitter video tab
            rule("com.twitter.android", "X video feed", MatchType.VIEW_ID, "immersive_player"),

            // Reddit video feed
            rule("com.reddit.frontpage", "Reddit video feed", MatchType.VIEW_ID, "video_container_view_pager"),
        )

        private fun rule(pkg: String, label: String, type: MatchType, value: String) = FeedRuleEntity(
            id = "builtin_${pkg}_${value}".take(120),
            packageName = pkg,
            label = label,
            matchType = type,
            matchValue = value,
            enabled = true,
            builtIn = true,
        )

        /** Apps Bastion offers up front when the user first opens Guard. */
        val SUGGESTED_PACKAGES = listOf(
            "com.instagram.android" to BlockMode.FEED_ONLY,
            "com.google.android.youtube" to BlockMode.FEED_ONLY,
            "com.zhiliaoapp.musically" to BlockMode.FEED_ONLY,
            "com.facebook.katana" to BlockMode.FEED_ONLY,
            "com.snapchat.android" to BlockMode.FEED_ONLY,
            "com.reddit.frontpage" to BlockMode.FEED_ONLY,
            "com.twitter.android" to BlockMode.FEED_ONLY,
        )
    }
}
