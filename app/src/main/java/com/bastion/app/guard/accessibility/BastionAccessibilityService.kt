package com.bastion.app.guard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.FeedRuleEntity
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.MatchType
import com.bastion.app.data.repo.GuardRepository
import com.bastion.app.feature.panic.PanicActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Bastion Guard.
 *
 * This is the piece Android can do that iOS cannot: rather than blocking a whole
 * app, it recognises *which screen inside it* is open and interrupts only that.
 * Instagram still opens so you can message a friend; the moment Reels appears,
 * the door closes.
 *
 * Privacy contract, and it is not negotiable:
 *   - the tree is inspected for view identifiers and, for a small number of
 *     rules, tab labels and content descriptions;
 *   - message bodies, posts, passwords and field contents are never read into
 *     any variable that outlives the match, never stored and never transmitted;
 *   - nothing this service observes leaves the device by any path.
 * Learn Mode captures view identifiers only, never text.
 */
class BastionAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    private lateinit var graph: BastionGraph
    private lateinit var shield: ShieldOverlay

    @Volatile private var guardedApps: Map<String, GuardedAppEntity> = emptyMap()
    @Volatile private var rulesByPackage: Map<String, List<FeedRuleEntity>> = emptyMap()

    /**
     * The words that mark a video title as one to close, mirrored so the scan
     * stays synchronous. Empty until the blocklist has been read, which means
     * this guard simply does nothing on the first few events rather than
     * blocking the tree walk to wait for a file.
     */
    @Volatile private var watchWords: List<String> = emptyList()

    /**
     * Every app on this phone that can open a web page.
     *
     * Asked of the package manager rather than listed by me, because listing
     * them is the mistake this keeps making. Chrome, Firefox, Samsung Internet,
     * the Google app, a reader, a shopping app with a built-in browser — the
     * one that matters is always the one nobody thought to name, and the phone
     * already knows the answer.
     *
     * Computed once when the service connects. Installing a new browser needs
     * Guard restarted to be seen, which is a fair trade for not walking this
     * list on every accessibility event.
     */
    @Volatile private var webCapableApps: Set<String> = emptySet()

    /**
     * The last settings seen, mirrored so [evaluate] stays synchronous.
     *
     * The whole object rather than a copied-out field: it used to keep only
     * `lockdownUntil`, and once lockdowns gained a monotonic anchor that single
     * field stopped being the answer to "is a lockdown running" — a user who
     * rolled the device clock forward got every guarded app back while the
     * lockdown was still, by the elapsed clock, very much running.
     */
    @Volatile private var settings: com.bastion.app.data.prefs.Settings =
        com.bastion.app.data.prefs.Settings()

    private var lastScanAt = 0L
    private var lastInterruptAt = 0L

    /** Throttles the lockdown wall's re-raise; see [holdWall]. */
    private var lastWallRaiseAt = 0L

    /** Throttles the settings wall; see [guardSettingsScreen]. */
    private var lastSettingsWallAt = 0L

    /**
     * Throttles the *reading* the settings wall does, which is the expensive
     * half and the one that runs when nothing is found.
     *
     * The wall's own cooldown only starts once a wall has gone up, so on a
     * screen that never matches there was nothing holding the walk back at all
     * — and a launcher emits content changes continuously while a man simply
     * looks at his home screen. Short enough to be invisible: 150ms is well
     * inside the time it takes to move a thumb to a menu item.
     */
    private var lastSettingsScanAt = 0L

    /**
     * The class of the window currently in front, remembered.
     *
     * A content-changed event carries the class of the *view* that changed, not
     * of the screen it changed on, so the guarded-screen check had nothing
     * usable to match against when run from there. Keeping the last window-state
     * class is what lets the check run continuously rather than once on arrival.
     */
    private var foregroundClassName: String? = null

    /**
     * Whether the window in front is a browser custom tab.
     *
     * Kept beside the class it is derived from because it has to survive
     * content-changed events, which carry the class of the view that changed
     * rather than of the window it changed in. See the window-state branch above.
     */
    /**
     * Every enabled address rule, from every service, kept flat.
     *
     * Address rules are not scoped to an app and never should have been. A URL
     * is the same URL in Chrome, in the Google app's tab, in the window a link
     * opens inside another app — and eight generations of rules were spent
     * discovering that the browser which matters is always the one nobody
     * listed. So there is no list any more: these apply wherever an address bar
     * is found, and what keeps that safe is what an address bar has to look
     * like rather than whose app it is in.
     */
    @Volatile private var urlRules: List<FeedRuleEntity> = emptyList()

    /** Whether a title rule is switched on; see [checkWatchTitles]. */
    @Volatile private var titleRuleOn = false

    /** Asked before every re-raise, so the wall never races the lock screen. */
    private val keyguard: android.app.KeyguardManager? by lazy {
        getSystemService(android.app.KeyguardManager::class.java)
    }

    /** Consecutive scans that have seen the player; see [checkFeed]. */
    private var feedHitStreak = 0
    private var foregroundPackage: String? = null
    private var foregroundSince = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        graph = BastionGraph.from(this)
        shield = ShieldOverlay(this)
        running.value = true

        scope.launch {
            graph.guard.guardedApps.collect { apps ->
                guardedApps = apps.filter { it.enabled }.associateBy { it.packageName }
            }
        }
        scope.launch {
            graph.guard.feedRules.collect { rules ->
                val on = rules.filter { it.enabled }
                rulesByPackage = on.groupBy { it.packageName }
                urlRules = on.filter { it.matchType == MatchType.URL }
                titleRuleOn = on.any { it.matchType == MatchType.TITLE }
            }
        }
        scope.launch {
            graph.settings.settings.collect { settings = it }
        }
        scope.launch {
            watchWords = runCatching { graph.guard.filterData().onScreen }.getOrDefault(emptyList())
        }
        webCapableApps = findWebCapableApps()
    }

    /**
     * Whoever the phone says can open `http://`, plus the web views that live
     * inside another app and never register for it.
     *
     * Messenger does not advertise itself as a browser and opens links in a web
     * view of its own; so do Facebook and Instagram. They are named explicitly
     * because the package manager will not name them, and they are the ones a
     * link most often arrives in.
     */
    private fun findWebCapableApps(): Set<String> {
        val known = GuardRepository.REAL_BROWSERS
        val resolved: Set<String> = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { info -> info.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
        return known + resolved + IN_APP_WEB_VIEWS
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Ahead of the system-package filter, and on every event type rather
        // than only on app switches. Both of those were costing real time.
        //
        // The unpin gesture travels through Recents, which is SystemUI, which
        // the filter below drops — so the wall used to wait until the user had
        // finished escaping and landed somewhere else entirely. Catching it here
        // puts the wall back *during* the gesture. And a content change or a
        // scroll means the user is already out and doing something, which is
        // exactly the moment to interrupt; waiting for the next app switch to
        // notice would be waiting for him to finish.
        if (pkg != packageName) holdWall(pkg)

        if (pkg == packageName || pkg in SYSTEM_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onForegroundChanged(pkg)
                foregroundClassName = event.className?.toString()
                guardSettingsScreen(pkg, foregroundClassName, foregroundClassName)
                evaluate(pkg, force = true)
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> guardLongPress(pkg, event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Also here, and this is what makes the wall arrive before the
                // button rather than after it.
                //
                // Settings' App info page is identified by Bastion's own name on
                // it, and at the moment the window opens that name has usually
                // not been drawn yet — so a check that ran only on arrival
                // looked at an empty screen, found nothing, and waited for the
                // next screen change, which was the confirmation dialog. The
                // page fires content-changed as it populates; running here
                // catches it the instant the title appears.
                guardSettingsScreen(pkg, foregroundClassName, event.className?.toString())
                evaluate(pkg, force = false)
            }
        }
    }

    private fun onForegroundChanged(pkg: String) {
        val now = System.currentTimeMillis()
        val previous = foregroundPackage
        if (previous != null && previous != pkg && foregroundSince > 0) {
            val elapsed = now - foregroundSince
            if (elapsed in 1_000..HALF_HOUR) recordUsage(previous, elapsed)
        }
        if (previous != pkg) {
            foregroundPackage = pkg
            foregroundSince = now
            currentApp.value = pkg
            // A streak is only meaningful within one app; carrying it across a
            // switch would let one stray hit in the old app count towards an
            // interruption in the new one.
            feedHitStreak = 0
            if (shield.isShowing) shield.hide()
        }
    }

    /**
     * Puts the lockdown wall back, every time something else takes the screen.
     *
     * Without Device Owner the wall is a screen like any other: the back and
     * recents gesture drops out of pinning and the lockdown is simply over,
     * because nothing was watching. The clocks survived — [Lockdown.isActive]
     * reads them from disk — but the only thing enforcing them was a window the
     * user had just closed. Leaving was one gesture.
     *
     * This is the answer that does not need Device Owner: not a wall that cannot
     * be left, but one that comes straight back. Every accessibility event while
     * the clock is running raises it again — an app switch, a scroll, a content
     * change, the Recents shell mid-gesture — so there is no window in which the
     * phone is usable. What that costs an escaper is the honest version:
     * Settings, Accessibility, Guard, off, with the wall landing on top of him
     * the whole way. Deliberate, not a reflex, which was the entire gap.
     *
     * The ceiling is unchanged and stated rather than hidden: switch Guard off
     * and this stops. Device Owner is still the only thing that closes it, and
     * the wall says so on its own face.
     */
    private fun holdWall(pkg: String) {
        // The setting that decided whether there is a wall at all. A lockdown
        // configured without the screen lock must not grow one here.
        if (!settings.lockdownLockScreen) return
        if (!com.bastion.app.guard.lockdown.Lockdown.isActive(settings)) return

        // The exceptions, and they are not negotiable.
        //
        // Android's lock screen carries an Emergency button, and the wall
        // deliberately leaves the keyguard reachable so it can be pressed. Two
        // things follow. The dialer that button opens must never have a lockdown
        // screen thrown over it, and the keyguard itself must not be raced —
        // this now fires on SystemUI events, and the keyguard is SystemUI, so
        // without the second check the wall would fight the lock screen for the
        // display in the one moment a man might be trying to call for help.
        //
        // A re-raise loop without these would quietly weld that valve shut,
        // which is the same mistake showWhenLocked made on the wall itself.
        if (pkg in EMERGENCY_PACKAGES) return
        if (keyguard?.isKeyguardLocked == true) return

        // Rate-limited rather than debounced: the first event through raises the
        // wall immediately, and this only stops the following few hundred
        // milliseconds of events from stacking launches on top of a wall that is
        // already on its way up. Nothing waits on this timer to be seen.
        val now = System.currentTimeMillis()
        if (now - lastWallRaiseAt < WALL_RAISE_COOLDOWN_MS) return
        lastWallRaiseAt = now

        // Self-healing by construction: if a launch is refused, the next window
        // change tries again. There is no state to get wrong.
        com.bastion.app.guard.lockdown.LockdownWallActivity.raise(this)
    }

    /**
     * The wall at the press itself, before any menu has finished opening.
     *
     * Everything else here infers that an uninstall is being started: a class
     * name that looks like a popup, a container identifier a launcher might
     * use, a name found somewhere in a tree. Every one of those is a guess
     * about a launcher I cannot see, and each guess held on the phones it was
     * written against and missed on this one — the wall kept arriving at the
     * confirmation dialog, one screen too late, which is one screen too many.
     *
     * A long press is delivered as its own event and it carries the label of
     * the view pressed. There is nothing to infer. The phone says which icon
     * was held down, and if it was Bastion's, the wall goes up on the spot.
     *
     * The service had never subscribed to the event, so Android was not
     * delivering it at all — the reason no amount of better matching helped.
     *
     * Long-pressing to move the icon or reach a shortcut walls too, and that is
     * the ask rather than a side effect: the point of the press being enough is
     * that nothing after it has to be reached to be stopped.
     */
    private fun guardLongPress(pkg: String, event: AccessibilityEvent) {
        if (!settings.tamperLockEnabled &&
            !com.bastion.app.guard.lockdown.Lockdown.isActive(settings)
        ) return
        if (!GuardedScreens.isLauncherApp(pkg)) return

        // The event's own strings only. No tree is read and nothing is
        // collected: what a man long-pressed is a single label, and reaching
        // past it into the screen would be taking more than the question needs.
        val pressed = buildList {
            event.text.forEach { text -> text?.toString()?.let { add(it) } }
            event.contentDescription?.toString()?.let { add(it) }
        }
        if (!GuardedScreens.isOurIcon(pressed, getString(com.bastion.app.R.string.app_name))) return

        val now = System.currentTimeMillis()
        if (now - lastSettingsWallAt < SETTINGS_WALL_COOLDOWN_MS) return
        lastSettingsWallAt = now
        com.bastion.app.guard.lockdown.SettingsWallActivity.raise(
            this,
            GuardedScreens.Guarded.UNINSTALL,
        )
    }

    /**
     * Shuts the two settings screens a locked-in man should not be standing on.
     *
     * Interrupts on *arrival* rather than after the fact. Every other guard here
     * notices a switch has been flipped and catches up; the accessibility screen
     * is where Guard gets turned off and the Private DNS screen is where the
     * resolver gets changed, so while the lock is on, being there at all is the
     * thing to interrupt. The wall itself is a door, not a cage — see
     * [SettingsWallActivity] for why leaving has to stay easy.
     *
     * Reads the tree only when the class name alone was not decisive, because
     * this runs on every window change in Settings and a walk per screen is
     * cheap while a walk per event would not be.
     */
    private fun guardSettingsScreen(pkg: String, className: String?, eventClassName: String?) {
        // A lockdown counts as well as the settings lock. Uninstalling during
        // one would take the running lockdown with it — the countdown, the
        // guarded apps and the partner's passcode all live in app data — so the
        // hour a man is most likely to try it is exactly the hour it must not
        // work.
        if (!settings.tamperLockEnabled &&
            !com.bastion.app.guard.lockdown.Lockdown.isActive(settings)
        ) return
        if (!GuardedScreens.isWatchedApp(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastSettingsWallAt < SETTINGS_WALL_COOLDOWN_MS) return

        // Cheap pass first: the class name on its own settles the accessibility
        // list on most builds, and nothing has to be read to know it.
        var guarded = GuardedScreens.detect(
            packageName = pkg,
            className = className,
            viewIds = emptySet(),
            texts = emptySet(),
            serviceLabel = "",
            dnsHostname = "",
            eventClassName = eventClassName,
        )

        if (guarded == null) {
            if (now - lastSettingsScanAt < SETTINGS_SCAN_THROTTLE_MS) return
            lastSettingsScanAt = now
            val root = rootInActiveWindow ?: return
            val ids = mutableSetOf<String>()
            val texts = mutableSetOf<String>()

            // On the home screen the search is narrowed to the menu itself.
            //
            // The workspace behind the menu has Bastion's name written under
            // its icon, so a whole-tree read says "Bastion is on screen" no
            // matter which app was long-pressed — and on the launchers that
            // keep the menu inside the workspace window, it says it when
            // nothing has been pressed at all. The wall would then stand over
            // the home screen permanently, which is not protection, it is a
            // phone nobody can use.
            //
            // Reading only inside the menu asks the question that was actually
            // meant: not "is Bastion somewhere behind this", but "is this menu
            // Bastion's".
            val onLauncher = GuardedScreens.isLauncherApp(pkg)
            val scope = if (onLauncher) menuIn(root) else null
            // No menu found on a launcher means the read is abandoned, not
            // widened. Falling back to the whole tree here is what made the
            // wall fire on other apps: the workspace carries Bastion's name
            // under its icon whichever icon was actually pressed, so a
            // whole-tree read answers "is Bastion on this screen" — always yes,
            // on a home screen — when the question was whether this menu is
            // Bastion's. The long press itself is the primary catch anyway.
            if (onLauncher && scope == null) return
            collectIdentity(scope ?: root, ids, texts)
            if (scope != null) {
                // The container's own identifier lives on the node itself, and
                // collectIdentity starts from its children downwards in the
                // usual case; adding it explicitly keeps the popup test honest
                // when the menu holds nothing else identifiable.
                scope.viewIdResourceName?.substringAfterLast('/')?.let { ids.add(it) }
                scope.recycle()
            }

            guarded = GuardedScreens.detect(
                packageName = pkg,
                className = className,
                viewIds = ids,
                texts = texts,
                serviceLabel = getString(com.bastion.app.R.string.accessibility_label),
                dnsHostname = settings.dnsHostname,
                appLabel = getString(com.bastion.app.R.string.app_name),
                eventClassName = eventClassName,
            )
        }

        if (guarded == null) return
        lastSettingsWallAt = now
        com.bastion.app.guard.lockdown.SettingsWallActivity.raise(this, guarded)
    }

    /**
     * The long-press menu inside a launcher's tree, if one is open.
     *
     * Returns the container so the caller can read that subtree alone. Null
     * when no menu is open, which is the ordinary state of a home screen and
     * has to stay cheap — this runs on content changes, and a launcher emits
     * plenty of those on its own.
     *
     * The caller owns what comes back and recycles it.
     */
    private fun menuIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                val id = node.viewIdResourceName?.substringAfterLast('/')
                val cls = node.className?.toString().orEmpty().lowercase()
                if (GuardedScreens.isLauncherMenuNode(id, cls)) {
                    // A copy, so the original can be recycled with the rest of
                    // the walk and the caller still has something to read.
                    return AccessibilityNodeInfo.obtain(node)
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it); borrowed.add(it) }
                }
            }
            return null
        } finally {
            recycleAll(borrowed)
        }
    }

    /**
     * View ids and short text, for deciding *which* settings screen this is.
     *
     * Bounded like every other walk here, and short-text-only on purpose. The
     * privacy contract at the top of this file is not suspended because the
     * foreground app happens to be Settings: only strings Bastion already owns
     * are ever compared against, nothing collected here outlives the match, and
     * long strings — which is what a message or a note looks like — are never
     * copied at all.
     */
    private fun collectIdentity(
        root: AccessibilityNodeInfo,
        ids: MutableSet<String>,
        texts: MutableSet<String>,
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                node.viewIdResourceName?.substringAfterLast('/')?.let { ids.add(it) }
                node.text?.toString()?.takeIf { it.length <= MAX_IDENTITY_TEXT }?.let { texts.add(it) }
                node.contentDescription?.toString()
                    ?.takeIf { it.length <= MAX_IDENTITY_TEXT }?.let { texts.add(it) }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it); borrowed.add(it) }
                }
            }
        } finally {
            recycleAll(borrowed)
        }
    }

    private fun recordUsage(pkg: String, elapsed: Long) {
        scope.launch {
            val day = LocalDate.now().toEpochDay()
            val dao = graph.database.guardDao()
            val existing = dao.usage(pkg, day)
            dao.upsertUsage(
                com.bastion.app.data.db.AppUsageEntity(
                    packageName = pkg,
                    epochDay = day,
                    foregroundMillis = (existing?.foregroundMillis ?: 0L) + elapsed,
                )
            )
        }
    }

    private fun evaluate(pkg: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now

        if (learnMode.value) {
            captureViewIds()
            return
        }

        val guarded = guardedApps[pkg]
        if (guarded == null) {
            // Leaving a guarded app has to take the veil with it. This branch
            // was a bare `?: return`, which was harmless only for as long as the
            // veil was never shown at all; the moment it works, an unguarded
            // return would leave a translucent sheet over the home screen and
            // every other app, with no way to clear it but killing the service.
            shield.hideDimVeil()

            // A browser does not have to be guarded for the feed rules to apply
            // to it, and requiring that was the hidden condition that made
            // "every browser is covered" untrue.
            //
            // Guarding an app is a statement about the app — close Instagram's
            // feed, leave its messages — and nobody thinks of the Google app
            // that way. He is not trying to limit the Google app. He is trying
            // to stop reels, and reels reached it through a web view he never
            // thought to name. Making him name it is the same enumeration
            // problem one layer up, just better hidden.
            //
            // So the universal address rules run here on their own. They are
            // safe to point at an app nobody chose because of what they require:
            // a string that is an address rather than a sentence, sitting where
            // an address bar sits, matching a path a man asked to have closed.
            checkUniversalFeed(pkg)
            // Unguarded too, because a browser is where a man watches YouTube
            // without ever having guarded anything.
            checkWatchTitles(pkg, "this page")
            return
        }

        // During a lockdown every guarded app is fully closed, whatever its own
        // mode says. A break-glass plan that still let the feed-only apps open
        // would not be worth pressing.
        if (com.bastion.app.guard.lockdown.Lockdown.isActive(settings)) {
            // Seconds below a minute, so a short rehearsal does not tell the
            // user "0m left" for its entire duration.
            val left = com.bastion.app.guard.lockdown.Lockdown.remainingSeconds(settings)
            val remaining = when {
                left >= 3600 -> "${left / 3600}h ${(left % 3600) / 60}m"
                left >= 60 -> "${left / 60}m"
                else -> "${left}s"
            }
            blockApp(guarded, "Lockdown. $remaining left.")
            return
        }

        when (guarded.mode) {
            BlockMode.FULL -> blockApp(guarded, "Closed for now.")
            BlockMode.SCHEDULE -> if (withinWindow(guarded.scheduleStart, guarded.scheduleEnd)) {
                blockApp(guarded, "Protected time.")
            }
            BlockMode.TIME_LIMIT -> checkTimeLimit(guarded)
            BlockMode.FEED_ONLY -> checkFeed(pkg, guarded)
        }

        // Whatever the mode says. Feed-only closes Shorts and leaves the watch
        // page alone, which is the right shape for a feed rule and the wrong
        // shape for this: a full-length video is not a feed, and it is where
        // the thing a man is actually avoiding sits. The other modes close the
        // app outright and never reach here anyway.
        checkWatchTitles(pkg, guarded.label)

        // Reads the global setting, which is the one the UI actually writes.
        //
        // This asked `guarded.grayscale` — a per-app column with no writer
        // anywhere in the app, so it was false for every row ever created and
        // the veil had never once been shown. Meanwhile the "Temptation
        // dampening" switch, and the grayscale step of the break-glass plan,
        // both wrote a global preference that nothing read.
        if (settings.grayscaleEnabled) shield.showDimVeil() else shield.hideDimVeil()
    }

    private fun checkTimeLimit(app: GuardedAppEntity) {
        scope.launch {
            val used = graph.database.guardDao()
                .usage(app.packageName, LocalDate.now().toEpochDay())?.foregroundMillis ?: 0L
            if (used >= app.timeLimitMinutes * 60_000L) {
                blockApp(app, "${app.timeLimitMinutes} minutes used today.")
            }
        }
    }

    private fun blockApp(app: GuardedAppEntity, reason: String) {
        if (System.currentTimeMillis() - lastInterruptAt < INTERRUPT_COOLDOWN_MS) return
        lastInterruptAt = System.currentTimeMillis()
        performGlobalAction(GLOBAL_ACTION_HOME)
        shield.show(
            title = app.label,
            message = reason,
            primaryLabel = "Back to solid ground",
            onPrimary = { shield.hide() },
            secondaryLabel = "I'm having an urge",
            onSecondary = {
                shield.hide()
                openPanic()
            },
        )
    }

    /**
     * What is on the screen rather than which screen it is.
     *
     * Every other rule here names a container: the Reels viewer, the Shorts
     * player, an address. That works because those screens are the problem
     * whatever is playing on them. YouTube is the case it cannot reach — the
     * watch page is the same page for a lecture and for the thing a man came
     * here to stop, and no view id will ever tell them apart. Neither will a
     * domain list, because youtube.com is not going on one.
     *
     * So this reads titles, and it is the only place in the app that reads what
     * is written on a screen rather than how the screen is built. The limits
     * are in TitleFilter and they are not incidental: video apps only, short
     * strings only, compared against a list Bastion shipped, and nothing kept
     * afterwards. A messaging app is never in the set this runs for.
     *
     * It is a net, not a wall, and the difference should be said plainly rather
     * than discovered: it catches what a title admits to. A video that says
     * nothing gets through, and an innocent one that happens to use a listed
     * word gets closed. The shield lets go on its own after eight seconds and
     * has a way out on it, because the cost of the second kind of mistake has
     * to stay small enough to live with.
     */
    private fun checkWatchTitles(pkg: String, label: String) {
        if (!titleRuleOn) return
        val words = watchWords
        if (words.isEmpty()) return
        val root = rootInActiveWindow ?: return
        // YouTube's own app, or a browser standing on YouTube.
        //
        // The app-only version answered half the question: the same video, the
        // same title, opened from a search result in a browser, went straight
        // past. What makes this safe to run outside the app is the same thing
        // that makes it safe inside it — it only reads where YouTube is what is
        // on screen, and "on screen" is decided by an address bar saying so,
        // not by a guess.
        if (pkg !in WATCH_APPS && !showsYouTube(root)) return

        val hit = firstBadTitle(root, words) ?: return
        if (System.currentTimeMillis() - lastInterruptAt < INTERRUPT_COOLDOWN_MS) return
        lastInterruptAt = System.currentTimeMillis()

        performGlobalAction(GLOBAL_ACTION_BACK)
        shield.show(
            title = "Not this one.",
            // The word, not the title. Saying which word was caught lets a man
            // judge the call himself; quoting the video back at him would put
            // the thing he is walking away from on the screen he walked to.
            message = "Closed on the word \"$hit\". The rest of $label is still yours.",
            primaryLabel = "Scroll something good",
            onPrimary = {
                shield.hide()
                openFeed()
            },
            secondaryLabel = "I'm having an urge",
            onSecondary = {
                shield.hide()
                openPanic()
            },
            autoDismissMillis = 8_000,
        )
    }

    /**
     * Whether the address on screen says this is YouTube.
     *
     * Reuses the address-bar test the feed rules already depend on rather than
     * inventing a second one, so a browser that hides its path — a custom tab, a
     * web view inside another app — still answers this correctly: the host is
     * the part those always show, and the host is all this asks about.
     *
     * This is the whole boundary for reading titles in a browser. Without it,
     * "read short text and compare it to a word list" would be running on every
     * page a man opens, which is not what was agreed and not what is needed.
     */
    private fun showsYouTube(root: AccessibilityNodeInfo): Boolean {
        val window = windowBoundsOf(root)
        val webViewTop = webViewTopIn(root)
        val widthCounts = FeedSurface.addressBarWidthCounts(
            realBrowser = root.packageName?.toString() in GuardRepository.REAL_BROWSERS,
            webViewFound = webViewTop > 0,
        )
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                val id = node.viewIdResourceName?.substringAfterLast('/')
                if (isAddressBarNode(node, id, window, webViewTop, widthCounts)) {
                    val text = node.text?.toString()
                    if (text != null && FeedSurface.urlMatches(text, "youtube.com")) return true
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it); borrowed.add(it) }
                }
            }
            return false
        } finally {
            recycleAll(borrowed)
        }
    }

    /**
     * The first listed word found in a title on screen, or null.
     *
     * Bounded like every other walk here. Content descriptions count as well as
     * text: YouTube writes the title of a thumbnail into the description of the
     * whole card, and on the watch page the player's own label often carries it
     * when the visible title has been collapsed.
     */
    private fun firstBadTitle(root: AccessibilityNodeInfo, words: List<String>): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                node.text?.toString()?.let { text ->
                    TitleFilter.match(text, words)?.let { return it }
                }
                node.contentDescription?.toString()?.let { text ->
                    TitleFilter.match(text, words)?.let { return it }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it); borrowed.add(it) }
                }
            }
            return null
        } finally {
            recycleAll(borrowed)
        }
    }

    /**
     * The rules that apply to an app: its own, or the universal set.
     *
     * A fallback rather than a union, and that is the part worth stating. If an
     * app has rules of its own then its switches on the Guard screen are the
     * whole truth about it — turning one off turns it off, with no second copy
     * of the same rule quietly still on under another name. An app nobody wrote
     * rules for gets the universal set instead, which is what makes "every
     * browser" true rather than "every browser I happened to list".
     */
    private fun rulesFor(pkg: String): List<FeedRuleEntity> =
        rulesByPackage[pkg].orEmpty().filter { it.matchType == MatchType.VIEW_ID } + urlRules

    /**
     * The address rules, in an app nobody guarded.
     *
     * Only for apps the phone says can open a web page, which keeps the tree
     * walk off every app a man opens all day. Everything else is the same
     * machinery as [checkFeed] — the same two-scan streak, the same cooldown —
     * because a false positive here costs exactly what it costs there.
     */
    private fun checkUniversalFeed(pkg: String) {
        if (pkg !in webCapableApps) return
        // The app's own rules when it has them, the universal set when it does
        // not — the same fallback guarded apps get, and the reason this is not
        // simply the universal list.
        //
        // The Google app has rules of its own precisely because its tab shows
        // no path, so the only rule that can fire there is its whole-site one.
        // Reading only the universal set here would have left that rule sitting
        // in the database, switched on, unreachable, and reported as working.
        val rules = rulesFor(pkg)
        if (rules.isEmpty()) return
        val root = rootInActiveWindow ?: return

        if (findMatch(root, rules) == null) {
            feedHitStreak = 0
            return
        }
        feedHitStreak++
        if (feedHitStreak < REQUIRED_FEED_HITS) return
        if (System.currentTimeMillis() - lastInterruptAt < INTERRUPT_COOLDOWN_MS) return
        lastInterruptAt = System.currentTimeMillis()
        feedHitStreak = 0

        performGlobalAction(GLOBAL_ACTION_BACK)
        shield.show(
            title = "Not this.",
            message = "That feed is closed wherever you open it. " +
                "The rest of the page is still yours.",
            primaryLabel = "Scroll something good",
            onPrimary = {
                shield.hide()
                openFeed()
            },
            secondaryLabel = "I'm having an urge",
            onSecondary = {
                shield.hide()
                openPanic()
            },
            autoDismissMillis = 8_000,
        )
    }

    /** The feed surgery: let the app run, close only the screen that hurts. */
    private fun checkFeed(pkg: String, app: GuardedAppEntity) {
        val rules = rulesFor(pkg)
        if (rules.isEmpty()) return
        val root = rootInActiveWindow ?: return

        val matched = findMatch(root, rules)

        // Two consecutive scans before acting, ~350ms apart.
        //
        // A reel unit flying past during a fling on the home feed can put a
        // matching node in the tree for a single frame. One scan is enough to
        // catch that and throw the user out of a feed he was allowed to be in;
        // requiring the signal to still be there on the next scan costs a third
        // of a second on a true positive and removes the whole class of
        // false ones. The streak resets the instant a scan comes back negative.
        if (matched == null) {
            feedHitStreak = 0
            return
        }
        feedHitStreak++
        if (feedHitStreak < REQUIRED_FEED_HITS) return

        run {
            if (System.currentTimeMillis() - lastInterruptAt < INTERRUPT_COOLDOWN_MS) return
            lastInterruptAt = System.currentTimeMillis()

            // Step back out of the feed first, then explain. Order matters: the
            // user should already be out before he reads anything.
            performGlobalAction(GLOBAL_ACTION_BACK)
            // The best loop in the app: it catches the scroll impulse and
            // hands it somewhere good in the same gesture, rather than only
            // saying no and leaving the man holding the urge with nowhere to
            // put it. Taking a feed away and offering nothing back is most of
            // why blockers get uninstalled.
            shield.show(
                title = "Not this.",
                message = "The rest of ${app.label} is still yours — " +
                    "or scroll something that builds you.",
                primaryLabel = "Scroll something good",
                onPrimary = {
                    shield.hide()
                    openFeed()
                },
                secondaryLabel = "I'm having an urge",
                onSecondary = {
                    shield.hide()
                    openPanic()
                },
                autoDismissMillis = 8_000,
            )
            feedHitStreak = 0
        }
    }

    /**
     * Bounded breadth-first walk. Bounded on purpose: an unbounded tree walk on
     * every content-changed event is how a guard app becomes a battery complaint.
     */
    private fun findMatch(root: AccessibilityNodeInfo, rules: List<FeedRuleEntity>): FeedRuleEntity? {
        val window = windowBoundsOf(root)
        // Only when a URL rule could fire. An ordinary app with view-id rules
        // has no web view and should not pay for a walk looking for one.
        val webViewTop =
            if (rules.any { it.matchType == MatchType.URL }) webViewTopIn(root) else 0
        // See FeedSurface.addressBarWidthCounts. Outside a real browser the
        // width guess would fire on a link somebody sent.
        val widthCounts = FeedSurface.addressBarWidthCounts(
            realBrowser = root.packageName?.toString() in GuardRepository.REAL_BROWSERS,
            webViewFound = webViewTop > 0,
        )
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        // Every node fetched via getChild() is owned by us. This runs on the
        // content-changed path many times a second, so leaking them is a real
        // battery and memory cost on the versions where recycling still matters.
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++

                val idSegment = node.viewIdResourceName?.substringAfterLast('/')
                for (rule in rules) {
                    val hit = when (rule.matchType) {
                        // Exact segment equality, not `contains`.
                        //
                        // `contains("reel_viewer")` also matched
                        // `reel_viewer_thumbnail` and `clips_viewer_preview` —
                        // the small inline previews Instagram embeds in the
                        // ordinary home feed. The rule is meant to name a
                        // destination, and a destination is one id, not a
                        // family of ids that happen to share a prefix.
                        MatchType.VIEW_ID ->
                            FeedSurface.idMatches(idSegment, rule.matchValue) &&
                                isPlayerSurface(node, window)
                        // A label is not a container, so "does it cover the
                        // window" is the wrong question — it never will. The
                        // right one is whether the label is *inside* the player:
                        // a covering vertical pager a few levels above it.
                        //
                        // These carried no geometry gate at all, on the grounds
                        // that nothing built-in uses them and Learn Mode only
                        // makes VIEW_ID rules. Both are true and neither closes
                        // the hole. A CONTENT_DESC rule reading "Reels" matches
                        // the bottom navigation button, which is present on
                        // every screen of Instagram, so feed-only silently
                        // becomes a total block the moment the app opens — this
                        // exact bug, from the built-in rules, is what
                        // MIGRATION_2_3 exists to delete. That migration only
                        // clears rows with builtIn = 1, and restoring a backup
                        // taken before it puts a user-owned copy straight back.
                        // A door that is currently hard to walk through is not
                        // the same as a closed one.
                        //
                        // The nav button fails this: its ancestors are the
                        // navigation bar and the screen root, neither of which
                        // is a covering vertical pager. A label genuinely inside
                        // the Reels viewer passes, because the pager above it is.
                        MatchType.CONTENT_DESC ->
                            node.contentDescription.equalsIgnoreCase(rule.matchValue) &&
                                hasVerticallyScrollableAncestor(node, window)
                        MatchType.TEXT ->
                            node.text.equalsIgnoreCase(rule.matchValue) &&
                                hasVerticallyScrollableAncestor(node, window)
                        // The address, not the page.
                        //
                        // No geometry gate on the *player* here, because in a
                        // browser there is no player to measure — the reel is a
                        // video element inside a web view with none of the
                        // identifiers an app exposes. The gate is on the address
                        // bar instead: see isAddressBarNode.
                        // Never here. A title rule reads what is written on
                        // the screen rather than how the screen is built, so it
                        // is answered by checkWatchTitles, on the surfaces where
                        // reading a title is something this app is allowed to
                        // do. Matching it in the general walk would turn every
                        // guarded app into one that reads its own text.
                        MatchType.TITLE -> false
                        MatchType.URL ->
                            isAddressBarNode(node, idSegment, window, webViewTop, widthCounts) &&
                                node.text?.toString()
                                    ?.let { FeedSurface.urlMatches(it, rule.matchValue) } == true
                    }
                    if (hit) return rule
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let {
                        queue.add(it)
                        borrowed.add(it)
                    }
                }
            }
            return null
        } finally {
            recycleAll(borrowed)
        }
    }

    /**
     * The window the tree belongs to, which is what every ratio is measured
     * against.
     *
     * It used to be `resources.displayMetrics`, which is the *display*. The two
     * are the same rectangle on a phone in normal use and nothing like each
     * other in split screen, where a genuine full-bleed player occupies half the
     * display and was therefore never recognised as one. Falls back to the
     * display if the root reports nothing usable, which is the old behaviour and
     * better than measuring against zero.
     */
    private fun windowBoundsOf(root: AccessibilityNodeInfo): android.graphics.Rect {
        val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        if (bounds.width() > 0 && bounds.height() > 0) return bounds
        val metrics = resources.displayMetrics
        return android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Whether this node is the short-form player *as the screen in front of you*,
     * rather than a tile of one embedded in something else.
     *
     * This is what separates Instagram's ordinary home feed from Reels. The home
     * feed embeds inline reel units and a reel tray whose view-ids are close
     * cousins of the ones the rules name, so id alone said "you are in Reels" the
     * moment the user scrolled their normal feed. Two properties tell the two
     * apart, and both have to hold:
     *
     *  - **It covers the window.** Not "is big" — *covers*: pinned to the top
     *    edge and reaching the bottom one. Size alone let a full-width 9:16 reel
     *    unit through in the middle of the home feed, because such a unit really
     *    is taller than 60% of the screen. Where it sits is the difference
     *    between the screen you are on and a post you are scrolling past.
     *  - **It pages vertically**, by its own account rather than by its shape.
     *    The home feed's reel tray scrolls horizontally, and so does the stories
     *    viewer — which is full-screen, and which the old shape-based guess
     *    therefore called a vertical pager and blocked as though it were Reels.
     */
    private fun isPlayerSurface(
        node: AccessibilityNodeInfo,
        window: android.graphics.Rect,
    ): Boolean {
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }

        if (!covers(bounds, window)) return false

        return scrollsVertically(node, bounds) || hasVerticallyScrollableAncestor(node, window)
    }

    /**
     * Asks the node which way it scrolls, rather than inferring it from its shape.
     *
     * The directional scroll actions have existed since API 23 and are the honest
     * answer. See [FeedSurface.scrollsVertically] for what the inference got
     * wrong — briefly, every full-screen horizontal pager on a portrait phone,
     * Instagram's stories viewer among them.
     */
    private fun scrollsVertically(
        node: AccessibilityNodeInfo,
        bounds: android.graphics.Rect,
    ): Boolean {
        val actions = node.actionList.orEmpty().map { it.id }
        return FeedSurface.scrollsVertically(
            canScrollUpDown = SCROLL_UP_DOWN.any { it in actions },
            canScrollLeftRight = SCROLL_LEFT_RIGHT.any { it in actions },
            scrollable = node.isScrollable,
            width = bounds.width(),
            height = bounds.height(),
        )
    }

    /**
     * Whether this node is a browser's address bar.
     *
     * Two ways, and the order matters. Every real browser names its address bar,
     * so the identifier settles it outright and costs nothing. The geometry
     * fallback is for the in-app browsers — the one Messenger opens, the one
     * Instagram opens — which are web views wrapped in a toolbar the host app
     * built itself and named however it liked.
     *
     * The text must also *be* an address rather than merely contain one, which
     * is what keeps a friend's link inside a conversation from walling the
     * conversation: a message has whitespace and fails immediately.
     */
    private fun isAddressBarNode(
        node: AccessibilityNodeInfo,
        idSegment: String?,
        window: android.graphics.Rect,
        webViewTop: Int,
        widthCounts: Boolean,
    ): Boolean {
        val text = node.text?.toString() ?: return false
        if (!FeedSurface.looksLikeUrl(text)) return false
        if (idSegment != null && idSegment in ADDRESS_BAR_IDS) return true

        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        // Above the web view is the toolbar the host app drew, whatever it named
        // the label or however small it made it. This is the one that catches
        // the in-app browsers; the width test below only ever caught real ones.
        if (FeedSurface.isBrowserChrome(bounds.bottom, webViewTop)) return true
        if (!widthCounts) return false

        return FeedSurface.isAddressBar(
            top = bounds.top,
            width = bounds.width(),
            windowTop = window.top,
            windowHeight = window.height(),
            windowWidth = window.width(),
        )
    }

    /**
     * Every address on screen, and whether each was taken as the address bar.
     *
     * The diagnostic half of [isAddressBarNode]: same tests, but reporting the
     * answer instead of acting on it. If a browser shows no address at all this
     * comes back empty, which is the one outcome no amount of matching can fix
     * and the one I could not see from here.
     */
    private fun seenAddresses(
        root: AccessibilityNodeInfo,
        window: android.graphics.Rect,
        webViewTop: Int,
    ): List<SeenAddress> {
        val out = LinkedHashMap<String, SeenAddress>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                val text = node.text?.toString()
                if (text != null && FeedSurface.looksLikeUrl(text)) {
                    val id = node.viewIdResourceName?.substringAfterLast('/')
                    val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    val byId = id != null && id in ADDRESS_BAR_IDS
                    val byChrome = FeedSurface.isBrowserChrome(bounds.bottom, webViewTop)
                    val byWidth = FeedSurface.isAddressBar(
                        top = bounds.top,
                        width = bounds.width(),
                        windowTop = window.top,
                        windowHeight = window.height(),
                        windowWidth = window.width(),
                    )
                    out[text] = SeenAddress(
                        text = text,
                        isAddressBar = byId || byChrome || byWidth,
                        reason = when {
                            byId -> "named as the address bar"
                            byChrome -> "above the page"
                            byWidth -> "a wide bar at the top"
                            else -> "on the page, treated as a link"
                        },
                    )
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it); borrowed.add(it) }
                }
            }
            return out.values.toList()
        } finally {
            recycleAll(borrowed)
        }
    }

    /**
     * The top edge of the page, so the toolbar above it can be told apart.
     *
     * Its own bounded walk, run only when a URL rule is actually in play, so
     * the common case of a feed rule in an ordinary app pays nothing for it.
     */
    private fun webViewTopIn(root: AccessibilityNodeInfo): Int {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                val cls = node.className?.toString().orEmpty()
                if (cls.contains("WebView", ignoreCase = true)) {
                    return android.graphics.Rect().also { node.getBoundsInScreen(it) }.top
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it); borrowed.add(it) }
                }
            }
            return 0
        } finally {
            recycleAll(borrowed)
        }
    }

    private fun covers(bounds: android.graphics.Rect, window: android.graphics.Rect): Boolean =
        FeedSurface.coversWindow(
            top = bounds.top,
            bottom = bounds.bottom,
            width = bounds.width(),
            windowTop = window.top,
            windowBottom = window.bottom,
            windowWidth = window.width(),
        )

    /**
     * The matched id often sits on a page *inside* the pager rather than on the
     * pager itself, so the scrollability lives a level or two up.
     *
     * The ancestor has to cover the window too. Without that, *any* tall
     * scrollable within four levels satisfied the vertical-pager test — and in
     * the ordinary home feed there is always one, because the feed itself is a
     * vertical scroller. The check was passing for the wrong reason on the exact
     * screen it exists to allow.
     */
    private fun hasVerticallyScrollableAncestor(
        node: AccessibilityNodeInfo,
        window: android.graphics.Rect,
    ): Boolean {
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        try {
            var current: AccessibilityNodeInfo? = node.parent?.also { borrowed.add(it) }
            var depth = 0
            while (current != null && depth < MAX_ANCESTOR_DEPTH) {
                val bounds = android.graphics.Rect().also { current!!.getBoundsInScreen(it) }
                if (scrollsVertically(current, bounds) && covers(bounds, window)) {
                    return true
                }
                current = current.parent?.also { borrowed.add(it) }
                depth++
            }
            return false
        } finally {
            recycleAll(borrowed)
        }
    }

    /** No-op from API 33, where the platform stopped pooling these. */
    private fun recycleAll(nodes: List<AccessibilityNodeInfo>) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) return
        nodes.forEach { runCatching { @Suppress("DEPRECATION") it.recycle() } }
    }

    /**
     * Learn Mode. When a target app is redesigned and a rule stops firing, the
     * user opens the offending screen with this on and Bastion lists the view
     * identifiers present, ready to become a new rule.
     *
     * Identifiers only. No text, ever — that is the whole reason this is safe.
     */
    private fun captureViewIds() {
        val root = rootInActiveWindow ?: return
        val window = windowBoundsOf(root)
        // Keyed by id so the same identifier seen twice does not appear twice,
        // and so a node that qualifies wins over one that does not.
        val found = LinkedHashMap<String, Boolean>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { id ->
                    // Whether a rule on THIS id would actually fire. Learn mode
                    // used to list every identifier on screen with no way to
                    // tell which would work, so a rule could be saved, look
                    // right, and silently never match — the failure the user
                    // only discovers by not being stopped.
                    val qualifies = isPlayerSurface(node, window)
                    found[id] = (found[id] ?: false) || qualifies
                }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    queue.add(it)
                    borrowed.add(it)
                }
            }
        }
        recycleAll(borrowed)

        val pkg = root.packageName?.toString().orEmpty()
        val rules = rulesFor(pkg)

        // The browser diagnosis, which exists because four attempts at the
        // in-app-browser path were made without ever seeing what the service
        // actually had in front of it. Guessing from a laptop is how a fix ships
        // that cannot work; this turns "still not working" into a sentence
        // naming the link that is broken.
        val webViewTop = webViewTopIn(root)
        val addresses = if (rules.any { it.matchType == MatchType.URL }) {
            seenAddresses(root, windowBoundsOf(root), webViewTop)
        } else emptyList()

        learnedIds.value = LearnCapture(
            packageName = pkg,
            // Ids that would actually block sort first; there are usually one
            // or two among a hundred.
            viewIds = found.entries
                .map { LearnedId(it.key, it.value) }
                .sortedByDescending { it.wouldBlock },
            // The live verdict: does this screen match a rule that already
            // exists? Answers "is what I am looking at right now covered?"
            // without having to leave the app and find out the hard way.
            blockedNow = findMatch(root, rules) != null,
            guardedAs = guardedApps[pkg]?.mode?.name,
            ruleCount = rules.size,
            urlRuleCount = rules.count { it.matchType == MatchType.URL },
            webViewFound = webViewTop > 0,
            addresses = addresses,
        )
    }

    /** Drops straight into the good feed, one tap from the block. */
    private fun openFeed() {
        runCatching {
            startActivity(
                Intent(this, com.bastion.app.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(
                        com.bastion.app.MainActivity.EXTRA_OPEN,
                        com.bastion.app.MainActivity.OPEN_FEED,
                    )
            )
        }
    }

    private fun openPanic() {
        val intent = Intent(this, PanicActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun withinWindow(startMinutes: Int, endMinutes: Int): Boolean {
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        // Windows routinely wrap midnight — 22:00 to 06:00 is the common case.
        return if (startMinutes <= endMinutes) now in startMinutes until endMinutes
        else now >= startMinutes || now < endMinutes
    }

    private fun CharSequence?.equalsIgnoreCase(other: String): Boolean =
        this != null && TextUtils.equals(this.toString().lowercase(), other.lowercase())

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        running.value = false
        // The overlay belongs to this service; if it goes away without taking
        // the veil down, nothing else can.
        shield.destroy()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running.value = false
        currentApp.value = null
        // Announced before the scope dies, because the most likely reason this
        // service is going away is that someone just switched it off in system
        // settings — and a guard that disappears silently is worse than no guard
        // at all, since the user goes on believing he is covered.
        notifyGuardDown()
        shield.destroy()
        scope.cancel()
        job.cancel()
        super.onDestroy()
    }

    /**
     * A quiet, persistent nudge that the wall is down.
     *
     * Not a punishment and not a nag loop — one notification that stays until
     * it is dealt with, tapping through to the Guard screen. If the user has a
     * partner set to hear about guard changes, the Guard screen is also where
     * he is prompted to tell him.
     */
    private fun notifyGuardDown() {
        runCatching {
            val open = android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, com.bastion.app.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = android.app.Notification.Builder(
                this,
                com.bastion.app.BastionApp.CHANNEL_PARTNER,
            )
                .setContentTitle("Bastion Guard is off")
                .setContentText("Feeds are no longer guarded. Tap to turn it back on.")
                .setSmallIcon(com.bastion.app.R.drawable.ic_shield)
                .setContentIntent(open)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()

            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(NOTIFICATION_GUARD_DOWN, notification)
        }
    }

    /** One identifier on screen, and whether a rule on it would actually fire. */
    data class LearnedId(val id: String, val wouldBlock: Boolean)

    /**
     * One address seen on screen, and why it did or did not count.
     *
     * Only strings that pass [FeedSurface.looksLikeUrl] are ever captured, which
     * is the same filter matching uses — so this shows exactly what the matcher
     * was allowed to look at and nothing more. A message with spaces in it never
     * reaches here, which is the privacy contract holding rather than being
     * suspended for the sake of a diagnostic.
     */
    data class SeenAddress(
        val text: String,
        /** Whether it was accepted as the address bar rather than a link. */
        val isAddressBar: Boolean,
        /** Why, in a word: the id, the toolbar, its width, or nothing. */
        val reason: String,
    )

    data class LearnCapture(
        val packageName: String,
        val viewIds: List<LearnedId>,
        /** Whether an existing rule already covers the captured screen. */
        val blockedNow: Boolean,
        /** How this app is guarded, if at all. Null when it is not. */
        val guardedAs: String? = null,
        /** Enabled rules that could fire here, and how many name an address. */
        val ruleCount: Int = 0,
        val urlRuleCount: Int = 0,
        /** Whether a web view was found, which is how browser chrome is located. */
        val webViewFound: Boolean = false,
        val addresses: List<SeenAddress> = emptyList(),
    )

    companion object {
        private const val SCAN_THROTTLE_MS = 350L
        private const val INTERRUPT_COOLDOWN_MS = 1_800L
        private const val MAX_NODES = 500

        /** Scans the player must be seen on before the user is interrupted. */
        private const val REQUIRED_FEED_HITS = 2

        /** How far up to look for the pager that owns the matched page. */
        private const val MAX_ANCESTOR_DEPTH = 4

        /**
         * The directional scroll actions, which are how a node says which way it
         * pages. Available since API 23, well under Bastion's floor of 26.
         */
        /**
         * The page actions belong here, and leaving them out was a real hole.
         *
         * Reels and Shorts are vertical ViewPager2s, and that is precisely the
         * widget which reports ACTION_PAGE_UP and ACTION_PAGE_DOWN rather than
         * the scroll pair — a pager moves in whole pages, so those are the
         * actions it advertises. A player exposing only the page actions
         * answered "no" to every question this asks, failed the vertical test,
         * and was never blocked, with no symptom other than the block silently
         * not happening.
         */
        private val SCROLL_UP_DOWN = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
        )

        /** The same, for the horizontal pagers this has to keep letting through. */
        private val SCROLL_LEFT_RIGHT = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        )
        /**
         * What browsers call their address bar.
         *
         * Chromium-derived browsers overwhelmingly keep `url_bar`, which covers
         * Chrome, Edge, Brave, Opera, Vivaldi and Kiwi at once. Firefox and
         * Samsung Internet each go their own way, and DuckDuckGo names it for
         * the omnibar it is.
         */
        private val ADDRESS_BAR_IDS = setOf(
            "url_bar",
            "location_bar_edit_text",
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_origin_view",
            "omnibarTextInput",
            "search_bar",
            "sanitized_url_text",
        )

        private const val NOTIFICATION_GUARD_DOWN = 4401
        private const val HALF_HOUR = 30 * 60 * 1000L

        /**
         * The web views that live inside another app and never register as a
         * browser, so the package manager will not name them.
         *
         * Only used to decide where it is worth walking the tree at all. What
         * rules apply is no longer a per-app question.
         */
        private val IN_APP_WEB_VIEWS = setOf(
            "com.facebook.orca",
            "com.facebook.katana",
            "com.instagram.android",
            "com.google.android.googlequicksearchbox",
        )

    /**
     * Where titles are read, and nowhere else.
     *
     * A short, explicit list rather than "any guarded app", because the moment
     * this could be pointed at a messaging app it would be reading messages —
     * and no setting, however well labelled, is worth that being one toggle
     * away. Adding to this list is a decision someone has to type out here.
     */
        private val WATCH_APPS = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.creator",
            "com.google.android.youtube.tv",
        )

        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.settings.intelligence",
        )

        /**
         * Deliberately short. This is not a delay before the wall appears — the
         * first event raises it at once — it is only how long a launch already
         * in flight is left alone before another is fired on top of it.
         *
         * It was 700ms, which was too generous by half: leave the wall twice
         * inside that window and the second escape got the rest of the budget
         * for free. At 150ms an activity launch has barely started, so nothing
         * is wasted, and there is no gap wide enough to do anything in.
         */
        private const val WALL_RAISE_COOLDOWN_MS = 150L

        /**
         * Short, because the screens this guards are ones a man is about to act
         * on and the wall has to beat his thumb.
         *
         * It was two seconds, chosen to leave room to walk away. That room turns
         * out to come from somewhere else: leaving goes to the home screen, and
         * the plain home screen matches nothing here — only a long-press popup
         * does. So the exit stays reachable at 250ms, and the wall now arrives
         * while the Uninstall button is still being looked at rather than after
         * it has been pressed.
         */
        private const val SETTINGS_WALL_COOLDOWN_MS = 250L

        /** See [lastSettingsScanAt]. */
        private const val SETTINGS_SCAN_THROTTLE_MS = 150L

        /** Long enough for a label, short enough never to be a message. */
        private const val MAX_IDENTITY_TEXT = 60

        /**
         * Never covered by the lockdown wall, at any point, for any reason.
         *
         * The emergency dialer and the in-call screen. Whether the call was
         * placed from the keyguard's Emergency button or dialled outright, the
         * screen that results is one of these, and [holdWall] leaves it alone.
         *
         * Deliberately wider than strictly necessary — every OEM ships a
         * different dialer id, and the failure mode of listing one too many is
         * that a lockdown does not re-raise over a phone call. The failure mode
         * of listing one too few is a man unable to see the call he is making
         * for help.
         */
        private val EMERGENCY_PACKAGES = setOf(
            "com.android.emergency",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
        )

        /**
         * The service shares a process with the app, so plain state flows are a
         * complete substitute for any cross-process plumbing.
         */
        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        private val currentApp = MutableStateFlow<String?>(null)

        /** Foreground package, used to tag urge logs with their context. */
        val foregroundApp: StateFlow<String?> = currentApp.asStateFlow()

        val learnMode = MutableStateFlow(false)
        val learnedIds = MutableStateFlow<LearnCapture?>(null)

        /** Whether the user has switched Bastion Guard on in system settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${BastionAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
