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
        val minutes = settings.current().coolingOffMinutes
        val now = System.currentTimeMillis()
        val request = GuardChangeRequestEntity(
            id = UUID.randomUUID().toString(),
            requestedAt = now,
            effectiveAt = now + minutes * 60L * 1000L,
            description = description,
            payload = payload,
        )
        guardDao.upsertChangeRequest(request)
        return request
    }

    /**
     * How long the pending unlock has left, or null if none was ever asked for.
     *
     * The settings wall needs to tell those two apart, and they are different
     * sentences: "your wait has forty minutes left" is encouragement, while
     * "nothing is counting down yet" tells a man the wait has not started and
     * standing here will not start it. Reporting zero for both would quietly
     * promise that the lock is about to lift.
     *
     * Zero means matured but not yet applied — the watchdog applies it on the
     * next reconcile, so it is honest to say the wait is over.
     */
    suspend fun pendingUnlockRemainingMillis(): Long {
        val pending = guardDao.pendingChanges().first()
            .firstOrNull { it.payload == "unlock" } ?: return -1L
        return (pending.effectiveAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    suspend fun cancelChange(id: String) = guardDao.setChangeStatus(id, ChangeStatus.CANCELLED)

    /**
     * Everything about the cooling-off delay that is not state.
     *
     * A nested object rather than the companion, which already exists and holds
     * the seeded content. Kept beside the code that applies the delay so the
     * wording, the choices and the arithmetic cannot drift apart.
     */
    object Delay {
        /**
         * The delay in words: "5 minutes", "2 hours", "24 hours".
         *
         * One formatter for every screen that mentions it, because four screens
         * hand-rolling "${hours} hours" is how a five-minute delay ends up
         * announced as "0 hours" on the one screen nobody re-read.
         */
        fun describe(minutes: Int): String = when {
            minutes < 60 -> if (minutes == 1) "1 minute" else "$minutes minutes"
            minutes % 60 == 0 -> (minutes / 60).let { if (it == 1) "1 hour" else "$it hours" }
            else -> "${minutes / 60}h ${minutes % 60}m"
        }

        /** The same thing, short enough for a chip: "5m", "2h", "24h". */
        fun describeShort(minutes: Int): String = when {
            minutes < 60 -> "${minutes}m"
            minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes / 60}h${minutes % 60}"
        }

        /**
         * The choices offered, in minutes.
         *
         * Five minutes is here so the lock, the settings wall and the unlock
         * flow can be exercised end to end without waiting out a real delay.
         * It is labelled as a test setting on screen rather than presented as
         * an equal option, because a delay a man can simply wait out in one
         * sitting is not protection — the entire mechanism depends on the
         * weak-moment self being unable to outlast it.
         */
        val CHOICES = listOf(5, 60, 120, 360, 1440)

        /** Below this, the delay is a rehearsal rather than a guard. */
        const val TEST_ONLY_BELOW_MINUTES = 60
    }

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
                // Two keys, because the unit changed and the number alone cannot
                // say which one it is.
                //
                // A request queued by an older build carries hours, and it may
                // not mature until after the upgrade. Guessing by magnitude was
                // the first attempt and it was wrong in the worst direction:
                // "5" is a legal hours value *and* the new five-minute setting,
                // so a man choosing five minutes would have been given five
                // hours. The payload says which unit it means instead.
                "cooloff" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                    settings.setCoolingOffMinutes(it * 60)
                }
                "cooloffm" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                    settings.setCoolingOffMinutes(it)
                }
                // Unlocking is itself a weakening and serves the same delay,
                // otherwise the lock would be a switch that turns itself off.
                "unlock" -> settings.setTamperLock(false)
                "vpn" -> {
                    settings.setVpnEnabled(false)
                    stopFilter = true
                }
                // "I'm done with Private DNS", once the wait has been served.
                com.bastion.app.guard.GuardWatchdog.PAYLOAD_STAND_DOWN_DNS -> {
                    settings.setDnsIntendedOn(false)
                    settings.setDnsOffSince(0L)
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
            // Only rules that identify the feed *viewer* itself.
            //
            // The tab buttons were matched here once, by content description
            // ("Reels", "Shorts"). That was wrong in a way that only shows up on
            // a real phone: those buttons live in the bottom navigation bar and
            // are therefore present on every screen of the app, so the rule
            // fired the moment Instagram opened and feed-only became a total
            // block. A rule has to name the destination, never the signpost.
            // Instagram Reels
            rule("com.instagram.android", "Instagram Reels", MatchType.VIEW_ID, "clips_viewer"),
            rule("com.instagram.android", "Instagram Reels (viewer)", MatchType.VIEW_ID, "reel_viewer"),

            // YouTube Shorts
            rule("com.google.android.youtube", "YouTube Shorts", MatchType.VIEW_ID, "reel_recycler"),
            rule("com.google.android.youtube", "YouTube Shorts (player)", MatchType.VIEW_ID, "reel_player_page_container"),
            rule("com.google.android.youtube", "YouTube Shorts (root)", MatchType.VIEW_ID, "reel_watch_fragment_root"),

            // TikTok — both the global and the regional package names
            rule("com.zhiliaoapp.musically", "TikTok For You", MatchType.VIEW_ID, "feed_tab_view"),
            rule("com.zhiliaoapp.musically", "TikTok For You (pager)", MatchType.VIEW_ID, "viewpager_container"),
            rule("com.ss.android.ugc.trill", "TikTok For You", MatchType.VIEW_ID, "feed_tab_view"),

            // Facebook Reels
            rule("com.facebook.katana", "Facebook Reels", MatchType.VIEW_ID, "video_home"),

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
