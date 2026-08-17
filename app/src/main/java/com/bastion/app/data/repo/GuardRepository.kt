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

    /**
     * Guard an app at a level, and switch on everything that level needs.
     *
     * The one entry point, because two of them is how the last hole appeared.
     * The level sheet did this correctly and the app picker did not — it wrote
     * the mode and stopped — so adding Instagram from the picker produced a card
     * reading "just the endless feed" above a line admitting nothing was
     * switched on inside it. That is the same four-part assembly the whole
     * restructure existed to delete, surviving in the most-used path of all.
     *
     * Feed-only is the level with a dependency: it means nothing without the
     * rules that say which screens the feed is. Every other level is complete on
     * its own — a fully blocked app needs no rule to describe what to close.
     */
    suspend fun guardAt(app: GuardedAppEntity) {
        guardDao.upsertApp(app)
        if (app.mode != BlockMode.FEED_ONLY) return
        guardDao.feedRules().first()
            .filter { it.packageName == app.packageName && !it.enabled }
            .forEach { guardDao.upsertRule(it.copy(enabled = true)) }
    }
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
            onScreen = content.blocklist().onScreen,
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
        /**
         * Title words, carried here because this is already the one place that
         * reads the blocklist off disk and caches it. The VPN and the browser
         * ignore the field; only the accessibility service asks for it.
         */
        val onScreen: List<String> = emptyList(),
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
        settings.setBuiltInRulesVersion(BUILT_IN_RULES_VERSION)
    }

    /**
     * Delivers built-in rules added since this install was first seeded.
     *
     * Without this, a new rule reaches new installs only. Everyone already
     * running Bastion has `guardSeeded` set, [seedIfEmpty] returns on its first
     * line, and the browser rules would have shipped to nobody who needed them —
     * a feature that reads as delivered in the release notes and is not on the
     * phone.
     *
     * Additive and one-way. Only ids absent from the table are inserted, so a
     * rule switched off stays off and one the user deleted from an earlier
     * generation is never resurrected: that was a decision he made, and undoing
     * it silently is precisely what the seeding flag exists to prevent. The
     * version is what draws that line — anything in a generation already offered
     * is his to have removed.
     */
    suspend fun syncBuiltInRules() {
        val current = settings.current()
        // Never on a fresh install: seedIfEmpty owns that, and running both
        // would insert the same rows twice on first launch.
        if (!current.guardSeeded) return
        if (current.builtInRulesVersion >= BUILT_IN_RULES_VERSION) return

        val existing = guardDao.feedRules().first().map { it.id }.toSet()
        val added = builtInFeedRules().filterNot { it.id in existing }
        if (added.isNotEmpty()) guardDao.upsertRules(added)

        // Generation 11 deletes rather than disables, and that is the point.
        //
        // Everything before it tried to answer "which app or window is this?" —
        // a row per browser, then a row for any unlisted app, then a row for the
        // window a link opens in. Five groups on the Guard screen for one
        // decision, and the browser that mattered was always the one not on the
        // list. Those rows are gone: a built-in rule that belongs to no service
        // has nothing left to mean, and leaving them switched off would leave
        // the confusion behind while removing the function.
        //
        // Only built-in rows are touched. Anything captured with Learn Mode
        // belongs to the man who captured it and is never swept up here.
        if (current.builtInRulesVersion < 11) {
            val services = builtInFeedRules().map { it.packageName }.toSet()
            guardDao.feedRules().first()
                .filter { it.builtIn }
                .filter { it.packageName !in services }
                .forEach { guardDao.deleteRule(it.id) }

            // And the story rule, which was never a reels rule.
            //
            // Instagram named stories "reels" years before Reels existed, and
            // named Reels "clips" when it shipped. So `reel_viewer` is the
            // story viewer, and a rule labelled "Instagram Reels (viewer)" was
            // throwing a man out of the app every time he opened a friend's
            // story — feed-only guarding doing the exact thing it exists to
            // prevent, under the name of the thing it was meant to catch.
            guardDao.feedRules().first()
                .filter { it.builtIn && it.matchType == MatchType.VIEW_ID }
                .filter { it.packageName == "com.instagram.android" && it.matchValue == "reel_viewer" }
                .forEach { guardDao.deleteRule(it.id) }
        }

        // Names, brought up to date. Nothing else about the row is touched.
        //
        // A rule keeps its id across generations, so a row already on the phone
        // never picks up a rename — and generation 11 renamed every surviving
        // one, because "Instagram Reels" and "Chrome · Instagram reels" became
        // "Reels, in the app" and "Reels, in a browser" under a single heading.
        //
        // `enabled` comes from disk rather than from the built-in definition, so
        // this can never quietly switch anything on or off.
        val labels = builtInFeedRules().associate { it.id to it.label }
        guardDao.feedRules().first()
            .filter { it.builtIn }
            .forEach { row ->
                val label = labels[row.id] ?: return@forEach
                if (label != row.label) guardDao.upsertRule(row.copy(label = label))
            }

        settings.setBuiltInRulesVersion(BUILT_IN_RULES_VERSION)
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
            // Days only past a day. "24 hours" is the span a man recognises and
            // "1 day" is the one he rounds off in his head to "tomorrow, some
            // time" — and this number is the whole promise the lock makes.
            minutes > 1440 && minutes % 1440 == 0 ->
                (minutes / 1440).let { if (it == 1) "1 day" else "$it days" }
            minutes % 60 == 0 -> (minutes / 60).let { if (it == 1) "1 hour" else "$it hours" }
            else -> "${minutes / 60}h ${minutes % 60}m"
        }

        /** The same thing, short enough for a chip: "12h", "24h", "3d". */
        fun describeShort(minutes: Int): String = when {
            minutes < 60 -> "${minutes}m"
            minutes > 1440 && minutes % 1440 == 0 -> "${minutes / 1440}d"
            minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes / 60}h${minutes % 60}"
        }

        /**
         * The choices offered, in minutes: half a day, then a day, then longer.
         *
         * Nothing shorter, and that is the whole design rather than a
         * preference. This delay exists to be longer than a weak moment, and a
         * weak moment can last an evening — anything a man can simply sit
         * through in one go is a delay he will sit through, at the exact hour it
         * was supposed to stop him. Twelve hours is the shortest span that
         * guarantees sleep falls inside it, which is the thing actually doing
         * the work: nobody stays in the same state of mind across a night.
         *
         * The list used to open with five minutes, so the lock could be
         * exercised end to end without waiting. That was a real convenience for
         * exactly one person — whoever was testing it — and a real hole for
         * everybody else, because it sat in the picker looking like a choice.
         */
        val CHOICES = listOf(720, 1440, 2880, 4320, 10080)

        /**
         * The floor, applied on read as well as offered in the picker.
         *
         * A phone that was set to five minutes under an older build still has
         * five minutes written to disk, and dropping the option from the list
         * would leave that setting live and unreachable — the worst of both,
         * since it is now invisible as well as too short. Raising it is a
         * tightening, which this app has always allowed to happen immediately.
         */
        val MINIMUM_MINUTES = CHOICES.min()
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
                // A whole app's feed rules at once.
                //
                // The screen switches them as a group, because two rules for the
                // same destination are an implementation detail of how the screen
                // is recognised rather than two separate decisions. Queuing one
                // request per matcher would have filled the waiting list with
                // items nobody chose individually and let half of them mature
                // into a half-blocked feed that looks like a bug.
                "rulegroup" -> {
                    val pkg = parts.getOrNull(1)
                    if (pkg != null) {
                        guardDao.feedRules().first()
                            .filter { it.packageName == pkg && it.enabled }
                            .forEach {
                                guardDao.upsertRule(
                                    it.copy(enabled = false, updatedAt = System.currentTimeMillis())
                                )
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
                // Dimming used to write straight through, ignoring the lock the
                // card above it advertised. Now it queues like everything else,
                // which means it also has to be applied when the wait is served
                // — a delay that expires and does nothing is worse than no delay
                // at all, because a man waits it out and concludes the whole
                // mechanism is theatre.
                "grayscale" -> settings.setGrayscale(false)
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
        /**
         * One group per service, not one per app and one per browser.
         *
         * The old shape asked "which app is this?" and answered it twice: a
         * view-id rule under Instagram, and a copy of every address rule under
         * Chrome, under Firefox, under Messenger, under a sentinel for
         * everything unlisted, under another for the window a link opens in.
         * Five places to look for one decision, and the browser that mattered
         * was always the one nobody had listed.
         *
         * A man does not think "block this in Chrome". He thinks "block
         * Instagram reels", and he does not care which window they arrive in.
         * So a service owns everything about itself: how its feed looks inside
         * its own app, and what its address looks like anywhere at all. The
         * address rules are not scoped to a browser because an address is not a
         * property of a browser — see rulesFor, which applies them everywhere.
         */
        fun builtInFeedRules(): List<FeedRuleEntity> = listOf(
            // --- Instagram -------------------------------------------------
            //
            // `clips_viewer` is Reels. `reel_viewer` is *stories*, and that is
            // not a typo in either direction: Instagram called stories "reels"
            // years before the Reels product existed, and when Reels shipped it
            // was named "clips" internally. So the rule labelled "Instagram
            // Reels (viewer)" was closing the app every time a friend's story
            // was opened — the exact failure feed-only guarding exists to
            // avoid, wearing the name of the thing it was supposed to catch.
            //
            // Removed rather than renamed. There is no story rule to want here.
            rule(INSTAGRAM, "Reels, in the app", MatchType.VIEW_ID, "clips_viewer"),
            rule(INSTAGRAM, "Reels, in a browser", MatchType.URL, "instagram.com/reel"),
            rule(INSTAGRAM, "All of Instagram, in a browser", MatchType.URL, "instagram.com", enabled = false),

            // --- YouTube ---------------------------------------------------
            rule(YOUTUBE, "Shorts, in the app", MatchType.VIEW_ID, "reel_recycler"),
            rule(YOUTUBE, "Shorts, in the app (player)", MatchType.VIEW_ID, "reel_player_page_container"),
            rule(YOUTUBE, "Shorts, in the app (root)", MatchType.VIEW_ID, "reel_watch_fragment_root"),
            rule(YOUTUBE, "Shorts, in a browser", MatchType.URL, "youtube.com/shorts"),
            // The one rule in the app that reads what is written rather than
            // how the screen is built. It carries no match value because the
            // words are the shipped list; see TitleFilter.
            rule(YOUTUBE, "Videos with explicit titles, app and browser", MatchType.TITLE, "adult"),
            rule(YOUTUBE, "All of YouTube, in a browser", MatchType.URL, "youtube.com", enabled = false),

            // --- TikTok ----------------------------------------------------
            rule(TIKTOK, "For You, in the app", MatchType.VIEW_ID, "feed_tab_view"),
            rule(TIKTOK, "For You, in the app (pager)", MatchType.VIEW_ID, "viewpager_container"),
            rule(TIKTOK_LITE, "For You, in the app", MatchType.VIEW_ID, "feed_tab_view"),
            rule(TIKTOK, "For You, in a browser", MatchType.URL, "tiktok.com/foryou"),
            rule(TIKTOK, "Share links (vt.tiktok.com)", MatchType.URL, "vt.tiktok.com"),
            rule(TIKTOK, "All of TikTok, in a browser", MatchType.URL, "tiktok.com", enabled = false),

            // --- Facebook --------------------------------------------------
            rule(FACEBOOK, "Reels, in the app", MatchType.VIEW_ID, "video_home"),
            rule(FACEBOOK, "Reels, in a browser", MatchType.URL, "facebook.com/reel"),
            rule(FACEBOOK, "Watch, in a browser", MatchType.URL, "facebook.com/watch"),
            // Nobody sends facebook.com/reel/1234; Facebook rewrites it to an
            // fb.watch link on the way out, and that is the door reels actually
            // come through.
            rule(FACEBOOK, "Share links (fb.watch)", MatchType.URL, "fb.watch"),
            // On, and it is the one default here that costs something.
            //
            // Messenger's web view, a custom tab and the Google app's tab all
            // show an origin with no path after it, so no path rule can ever
            // fire in them — asked about eight times, answered eight ways, and
            // this is the only answer that works. It now applies in a full
            // browser too, which is wider than Messenger alone was. One switch,
            // in the place a man would look for it.
            rule(FACEBOOK, "All of Facebook, in a browser", MatchType.URL, "facebook.com"),

            // --- The rest --------------------------------------------------
            rule(SNAPCHAT, "Spotlight, in the app", MatchType.VIEW_ID, "spotlight"),
            rule(SNAPCHAT, "Spotlight, in a browser", MatchType.URL, "snapchat.com/spotlight"),

            rule(X, "Video feed, in the app", MatchType.VIEW_ID, "immersive_player"),

            rule(REDDIT, "Video feed, in the app", MatchType.VIEW_ID, "video_container_view_pager"),
            rule(REDDIT, "Popular, in a browser", MatchType.URL, "reddit.com/r/popular"),
        )

        private const val INSTAGRAM = "com.instagram.android"
        private const val YOUTUBE = "com.google.android.youtube"
        private const val TIKTOK = "com.zhiliaoapp.musically"
        private const val TIKTOK_LITE = "com.ss.android.ugc.trill"
        private const val FACEBOOK = "com.facebook.katana"
        private const val SNAPCHAT = "com.snapchat.android"
        private const val X = "com.twitter.android"
        private const val REDDIT = "com.reddit.frontpage"

        /**
         * Bumped whenever the built-in set changes.
         *
         * 1 was the original app rules. 2 added browser URL rules. 3 replaced
         * them with host rules, 4 brought paths back, 5 turned the host rules
         * on for in-app browsers, 6 repaired names, 7 closed Facebook in
         * Messenger, 8 added a rule set for any app at all, 9 added the Google
         * app, 10 added the custom-tab window.
         *
         * 11 throws most of that away. Eight generations of this were spent
         * answering "which app or window is this?", and every one of them was
         * beaten by a window nobody had listed. Rules now belong to the service
         * they block and their addresses apply everywhere, so there is nothing
         * left to enumerate — and the two sentinel groups that came out of the
         * old shape are deleted rather than left switched off.
         */
        const val BUILT_IN_RULES_VERSION = 11

        /**
         * The browsers whose address bar genuinely spans the screen.
         *
         * Nothing to do with which rules apply — those apply everywhere now.
         * This is only about the width test, which guesses at an address bar
         * from "wide, and near the top". In one of these that guess is sound:
         * an omnibox is built that way. Anywhere else it is a guess about a
         * layout nobody designed to be guessed at, and address rules now reach
         * apps full of things a man wrote, so outside this list the guess is
         * withdrawn unless a web view is open. See FeedSurface.addressBarWidthCounts.
         */
        internal val REAL_BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
            "com.chrome.canary", "org.mozilla.firefox", "org.mozilla.firefox_beta",
            "com.sec.android.app.sbrowser", "com.brave.browser", "com.microsoft.emmx",
            "com.opera.browser", "com.opera.mini.native",
            "com.duckduckgo.mobile.android", "com.vivaldi.browser",
            "com.kiwibrowser.browser", "org.torproject.torbrowser",
            "com.ecosia.android", "com.yandex.browser", "com.UCMobile.intl",
            "com.mi.globalbrowser", "com.huawei.browser",
        )

        private fun rule(
            pkg: String,
            label: String,
            type: MatchType,
            value: String,
            /**
             * Whether it arrives switched on.
             *
             * The whole-site browser rules ship off: they are a bigger hammer
             * than most men want, and a rule that closes Facebook entirely
             * should be chosen rather than discovered.
             */
            enabled: Boolean = true,
        ) = FeedRuleEntity(
            id = "builtin_${pkg}_${value}".take(120),
            packageName = pkg,
            label = label,
            matchType = type,
            matchValue = value,
            enabled = enabled,
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
