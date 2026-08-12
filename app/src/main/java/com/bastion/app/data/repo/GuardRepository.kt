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

        val onDisk = guardDao.feedRules().first()
        val existing = onDisk.map { it.id }.toSet()
        val added = builtInFeedRules().filterNot { it.id in existing }
        if (added.isNotEmpty()) guardDao.upsertRules(added)

        // The one place this reaches back and changes something already there.
        //
        // Generation 3 shipped whole-site browser rules switched on, and that
        // was the app's choice rather than the user's: it closed all of Facebook
        // in a browser when what he wanted closed was the reels. Now that the
        // path rules can fire, those are on and this correction turns the
        // whole-site ones off — undoing a default this app set, not a decision a
        // man made. He can switch them back on from the same screen, and they
        // are the answer when a browser will not show a path.
        if (current.builtInRulesVersion == 3) {
            val retired = setOf("instagram.com", "facebook.com", "tiktok.com")
            onDisk.filter { it.builtIn && it.matchType == MatchType.URL && it.matchValue in retired }
                .filter { it.enabled }
                .forEach { guardDao.upsertRule(it.copy(enabled = false)) }
        }

        // And the correction to that correction, for the in-app browsers only.
        //
        // Generation 4 switched every whole-site rule off on the reasoning that
        // a path is narrower and kinder. That is true in Chrome and false in
        // Messenger, which shows no path at all — so for those three the switch
        // took away the only rule that could ever fire. Turning them back on is
        // undoing my own default a second time rather than overriding a man's
        // choice, and it is the last time this flips: after here the switch on
        // the Guard screen is his.
        if (current.builtInRulesVersion < 5) {
            val sites = setOf("instagram.com", "facebook.com", "tiktok.com")
            guardDao.feedRules().first()
                .filter { it.builtIn && it.matchType == MatchType.URL }
                .filter { it.packageName in IN_APP_BROWSERS && it.matchValue in sites }
                .filter { !it.enabled }
                .forEach { guardDao.upsertRule(it.copy(enabled = true)) }
        }

        // Facebook, closed in Messenger, because that was asked for outright.
        //
        // Generation 5's note said the switch was his from then on, and this is
        // not a walk-back of that — it is the switch being used. The ask was
        // "have it be just blocking of Facebook in general for Messenger", made
        // after seeing exactly what it costs, which is more informed consent
        // than a default has any right to. Doing it here rather than leaving him
        // to find the toggle is the difference between a decision honoured and a
        // decision he has to implement himself.
        //
        // Messenger alone, and Facebook alone. The reels reach him through the
        // chat window, and nothing was said about Instagram or about the other
        // web views, so nothing else is touched.
        if (current.builtInRulesVersion < 7) {
            val facebook = setOf("facebook.com", "fb.watch")
            guardDao.feedRules().first()
                .filter { it.builtIn && it.matchType == MatchType.URL }
                .filter { it.packageName == "com.facebook.orca" && it.matchValue in facebook }
                .filter { !it.enabled }
                .forEach { guardDao.upsertRule(it.copy(enabled = true)) }
        }

        // Facebook, closed in the Google app, for the reason it was closed in
        // Messenger and after the same report arriving again.
        //
        // Its tab shows an origin and no path, so the path rules cannot fire
        // there however well they are written — and shipping them switched on
        // beside a site rule switched off would be shipping the appearance of
        // protection. This is a default this app sets, not a decision a man
        // made, and the switch on the Guard screen is his the moment he wants
        // it back.
        if (current.builtInRulesVersion < 9) {
            val facebook = setOf("facebook.com", "fb.watch")
            guardDao.feedRules().first()
                .filter { it.builtIn && it.matchType == MatchType.URL }
                .filter {
                    it.packageName == "com.google.android.googlequicksearchbox" &&
                        it.matchValue in facebook
                }
                .filter { !it.enabled }
                .forEach { guardDao.upsertRule(it.copy(enabled = true)) }
        }

        // Names, brought up to date. Nothing else about the row is touched.
        //
        // A rule keeps its id across generations, so a row already on the phone
        // never picks up a rename. Generation 5 renamed the web-view site rules
        // to "Scrolling Facebook" for a scroll gate that has since been taken
        // back out, and a phone that took that release is still showing the
        // name — which now describes behaviour the rule does not have. A wrong
        // name on the one switch a man is deciding about is worse than no name.
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
        ) + browserFeedRules()

        /**
         * The same feeds, reached through a browser instead of an app.
         *
         * Blocking Instagram's app and leaving instagram.com one tap away in
         * Chrome is a door with no wall behind it, and the in-app browser
         * Messenger opens when a friend sends a link is the same door again —
         * arguably the likelier one, since it arrives unasked in the middle of a
         * conversation.
         *
         * Generated rather than written out, because it is one destination list
         * against one browser list and hand-writing the cross product is how one
         * of them silently ends up missing a row. The feed-rules screen hides
         * apps that are not installed, so a man sees only the browsers he has.
         */
        /**
         * Bumped whenever a rule is added to the built-in set.
         *
         * 1 was the original app rules. 2 added the browser and in-app-browser
         * URL rules, which existing installs would otherwise never have seen.
         * 3 replaced those with host rules, because the path ones could not fire
         * against a browser that does not display a path. 4 brought the paths
         * back once the address bar was being found, and switched the
         * whole-site rules off. 5 turned them back on for the in-app
         * browsers only, where the domain is all that is ever shown. 6 only
         * repairs the names, after a scroll gate came and went. 7 adds the
         * share-link domains and closes Facebook in Messenger, asked for
         * outright. 8 adds the rules that apply to any app at all, so a
         * browser nobody listed is still covered. 9 adds the Google app,
         * whose tab shows an origin and no path.
         */
        const val BUILT_IN_RULES_VERSION = 9

        private fun browserFeedRules(): List<FeedRuleEntity> =
            BLOCKED_PATHS.map { (label, url) ->
                rule(ANY_APP, "Any app · $label", MatchType.URL, url)
            } +
            BROWSER_PACKAGES.flatMap { (pkg, name) ->
                val inApp = pkg in IN_APP_BROWSERS
                BLOCKED_PATHS.map { (label, url) ->
                    rule(pkg, "$name · $label", MatchType.URL, url)
                } + BLOCKED_SITES.map { (label, url) ->
                    // On for the in-app browsers, off for the real ones, and the
                    // split is what the browser can actually show rather than a
                    // preference. See IN_APP_BROWSERS.
                    rule(pkg, "$name · $label", MatchType.URL, url, enabled = inApp)
                }
            }

        /**
         * The web views that live inside another app, where only the site rule
         * can work — and where it costs nothing.
         *
         * Two reports bracket this. With host rules, Messenger was recognised
         * and Facebook closed. With path rules it stopped working entirely.
         * Between them that says what no amount of reasoning from here could:
         * the address these show is the domain alone, with no path to match
         * against. A path rule has nothing to compare and never will.
         *
         * Which makes the trade that was worth arguing about in Chrome
         * disappear here. Closing facebook.com inside *Messenger's* link viewer
         * costs a man nothing he cannot do in Messenger itself — his messages
         * are the app he is already standing in. The reason to prefer a path
         * was to keep web messaging open, and there is no web messaging to keep
         * open inside a messaging app.
         *
         * The path rules ship on for these too, harmlessly: if one of them ever
         * does show a path, the narrower rule is there and matches first.
         */
    /**
         * The browsers whose address bar genuinely spans the screen.
         *
         * The width test guesses at an address bar from "wide, and near the
         * top". In one of these that guess is sound — an omnibox is built that
         * way. Anywhere else it is a guess about a layout nobody designed to be
         * guessed at, and with address rules now applying to any guarded app,
         * "anywhere else" includes apps full of things a man wrote. So outside
         * this list the guess is withdrawn unless a web view is open; see
         * FeedSurface.addressBarWidthCounts.
         */
        internal val REAL_BROWSERS = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser",
            "com.kiwibrowser.browser",
            "org.torproject.torbrowser",
            "com.ecosia.android",
            "com.yandex.browser",
            "com.UCMobile.intl",
            "com.mi.globalbrowser",
            "com.huawei.browser",
        )

        internal val IN_APP_BROWSERS = setOf(
            "com.facebook.orca",
            "com.facebook.katana",
            "com.instagram.android",
            // The Google app, and it belongs here for the same reason the other
            // three do rather than because it is a social app.
            //
            // A link opened from search or from Discover lands in a tab whose
            // bar shows the origin and nothing else — `facebook.com`, with no
            // path after it — whether that tab is drawn by the Google app or
            // handed to Chrome as a custom tab. A path rule has nothing to
            // compare against and never will, so the site rule is the only rule
            // that can fire, exactly as in Messenger.
            "com.google.android.googlequicksearchbox",
        )

        /**
         * The feeds themselves, by path.
         *
         * These went out as host rules for one release because a path rule
         * could never fire — the address bar was not being found at all, so
         * nothing was matched against and the host was the only thing that
         * could work. Once the bar was located properly the path came back
         * within reach, and the path is what a man actually wants closed:
         * "recognises Facebook but not Facebook reels" is a blocker that has
         * taken his messages away to stop him watching videos.
         *
         * Path rules match by prefix along the path, so /reel covers /reel/<id>
         * and /reels alike — Facebook writes the same feed both ways.
         */
        private val BLOCKED_PATHS = listOf(
            "Instagram reels" to "instagram.com/reel",
            "Facebook reels" to "facebook.com/reel",
            "Facebook watch" to "facebook.com/watch",
            "YouTube Shorts" to "youtube.com/shorts",
            "TikTok feed" to "tiktok.com/foryou",
            "Snapchat Spotlight" to "snapchat.com/spotlight",
            "Reddit videos" to "reddit.com/r/popular",
        )

        /**
         * The package that means "any app not named below".
         *
         * Every browser used to need its own row, which made the list a
         * catalogue of the browsers I happened to think of. The one a man
         * actually reaches for is always the one that was missed — the Google
         * app opens links in a web view of its own, so does Gmail, so does
         * every reader and every chat app, and a rule that covered Chrome and
         * not those is a rule with a door beside it.
         *
         * So the browser list stops being the point. Guard any app in
         * feed-only mode and these apply to it, unless that app has rules of
         * its own — see rulesFor, which falls back rather than adding, so an
         * app named below still means exactly what its own switches say.
         */
        const val ANY_APP = "*"

        /** How the sentinel names itself on screen, where no package can be resolved. */
        const val ANY_APP_LABEL = "Any other app"

        /**
         * The whole site, for when the path cannot be seen.
         *
         * Some browsers show only the domain, and against those a path rule has
         * nothing to compare. These are the fallback for that, and they ship
         * switched off: closing Facebook entirely is a bigger hammer than most
         * men want, and it should be chosen rather than discovered. TikTok is
         * here rather than above because the whole site is the feed — there is
         * no rest-of-the-site to protect.
         */
        /**
         * Whole sites, for the browsers where a path cannot be seen.
         *
         * Subdomains come along for free — see FeedSurface.urlMatches, which
         * matches down the host rather than along it — so `web.facebook.com` and
         * `mbasic.facebook.com` need no rules of their own.
         *
         * The short-link domains do, because they are different sites. A reel
         * shared into a chat usually arrives as an `fb.watch` link, and that is
         * the single likeliest way a Facebook feed reaches a man who has just
         * asked for Facebook to be closed in Messenger. Blocking facebook.com
         * and leaving fb.watch open would be closing the front door of a
         * building with two.
         */
        private val BLOCKED_SITES = listOf(
            "All of Instagram" to "instagram.com",
            "All of Facebook" to "facebook.com",
            "Facebook share links" to "fb.watch",
            "All of TikTok" to "tiktok.com",
            "TikTok share links" to "vt.tiktok.com",
        )

        /**
         * Browsers, and the apps that carry one inside them.
         *
         * Messenger, Facebook and Instagram all open links in a web view of
         * their own rather than handing off to a browser, so they need the URL
         * rules as much as Chrome does — and for Facebook and Instagram those
         * sit alongside the view-id rules already covering their native feeds.
         */
        private val BROWSER_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox" to "Google app",
            "com.android.chrome" to "Chrome",
            "com.chrome.beta" to "Chrome",
            "org.mozilla.firefox" to "Firefox",
            "com.sec.android.app.sbrowser" to "Samsung Internet",
            "com.brave.browser" to "Brave",
            "com.microsoft.emmx" to "Edge",
            "com.opera.browser" to "Opera",
            "com.duckduckgo.mobile.android" to "DuckDuckGo",
            "com.facebook.orca" to "Messenger",
            "com.facebook.katana" to "Facebook",
            "com.instagram.android" to "Instagram",
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
