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
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.CategoryEntity
import com.bastion.app.data.db.Confidence
import com.bastion.app.data.db.MatchType
import com.bastion.app.data.db.Outcome
import com.bastion.app.data.db.PolicyEntity
import com.bastion.app.data.db.Response
import com.bastion.app.data.db.SignalEntity
import com.bastion.app.data.db.SurfaceEntity
import com.bastion.app.data.db.TargetType
import com.bastion.app.data.repo.GuardRepository
import com.bastion.app.guard.policy.Decision
import com.bastion.app.guard.policy.PolicyContext
import com.bastion.app.guard.policy.PolicyEngine
import com.bastion.app.guard.policy.ResolvedSurface
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
     * Every app the *system* considers a browser, asked rather than listed.
     *
     * This decides whether the width test is allowed to guess at an address bar
     * — see FeedSurface.addressBarWidthCounts — and it used to be a hardcoded
     * set of twenty package names. Which meant a browser downloaded tomorrow
     * was not one: its omnibox spans the screen exactly like Chrome's, and
     * Bastion refused to read it because nobody had typed its name into a list.
     *
     * An app that registers to open http and https with CATEGORY_BROWSABLE is a
     * browser. That is not a heuristic, it is the definition Android itself uses
     * to decide what goes in the "open with" list, and an ad-blocking browser
     * off the store satisfies it on the day it is installed.
     */
    @Volatile private var browserApps: Set<String> = emptySet()

    /** When [findBrowsers] last ran, so a miss can decide whether to ask again. */
    @Volatile private var lastBrowserScan = 0L

    /** When Learn Mode was armed, so it can disarm itself. 0 when it is off. */
    @Volatile private var learnArmedAt = 0L

    // --- Protection Model v2 ---------------------------------------------
    //
    // The decision used to be made here, inline, tangled with lockdown state,
    // learn mode, the veil and grayscale. It is now four pieces of data and one
    // call to a pure function, which is the whole point: the same decision can
    // be argued with on a laptop, and every hard bug this file has produced was
    // one nobody could reproduce without a phone in a hand.

    @Volatile private var signals: List<SignalEntity> = emptyList()
    @Volatile private var policies: List<PolicyEntity> = emptyList()
    @Volatile private var surfacesById: Map<String, SurfaceEntity> = emptyMap()
    @Volatile private var categoriesByKey: Map<String, CategoryEntity> = emptyMap()

    /**
     * Today's foreground time per package, so a budget can be judged without a
     * database read in the middle of a decision.
     *
     * Loaded once when the guard connects and kept current as time is recorded.
     * Reading it during the decision would either make the engine impure or make
     * the block asynchronous, and an asynchronous block is one that arrives
     * after the scroll.
     */
    @Volatile private var usageToday: Map<String, Long> = emptyMap()

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
     * Whether reading titles is switched on at all; see [checkWatchTitles].
     *
     * Derived from the signals rather than kept as a flag of its own, so there
     * is one answer to "is this on" and not two that can disagree.
     */
    private val titleRuleOn: Boolean
        get() = signals.any { it.matchType == MatchType.TITLE }

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
            graph.policy.signals.collect { rows -> signals = rows.filter { it.enabled } }
        }
        scope.launch {
            graph.policy.policies.collect { rows -> policies = rows.filter { it.enabled } }
        }
        scope.launch {
            graph.policy.surfaces.collect { rows -> surfacesById = rows.associateBy { it.id } }
        }
        scope.launch {
            graph.policy.categories.collect { rows ->
                categoriesByKey = rows.associateBy { it.key }
            }
        }
        scope.launch {
            usageToday = runCatching {
                graph.database.guardDao().usageOn(LocalDate.now().toEpochDay())
                    .associate { it.packageName to it.foregroundMillis }
            }.getOrDefault(emptyMap())
        }
        scope.launch {
            graph.settings.settings.collect { settings = it }
        }
        scope.launch {
            watchWords = runCatching { graph.guard.filterData().onScreen }.getOrDefault(emptyList())
        }
        refreshBrowsers()
        // A browser installed after this point has to count immediately.
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // Declared private rather than left to the default. From API 34 a
        // receiver registered without saying which it is throws unless every
        // action is a protected system broadcast, and "these three happen to be
        // protected today" is a thing to state rather than to rely on. Wrapped
        // anyway, because a throw here would take the whole service down at
        // connect — and a stale browser list is a gap, while no guard at all is
        // a hole.
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                packagesChanged,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
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
    private fun findBrowsers(): Set<String> {
        val resolved = listOf("http://example.com", "https://example.com").flatMap { url ->
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                packageManager
                    .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .mapNotNull { info -> info.activityInfo?.packageName }
            }.getOrDefault(emptyList())
        }.toSet()
        // The shipped names stay as a floor, so a query that comes back empty on
        // some OEM build cannot silently take every browser rule with it.
        return resolved + GuardRepository.REAL_BROWSERS
    }

    /**
     * Recomputed whenever the phone gains or loses an app.
     *
     * Computed once at connect, this went stale the moment a browser was
     * installed: the new one was not a browser as far as Bastion was concerned
     * until Guard was switched off and on again, which nobody does and nobody
     * should have to. Installing a browser is exactly when a man is most likely
     * to be looking for a way around this.
     */
    private fun refreshBrowsers() {
        browserApps = findBrowsers()
        webCapableApps = browserApps + IN_APP_WEB_VIEWS
        lastBrowserScan = System.currentTimeMillis()
    }

    /**
     * Whether this app can show a page, asking again if the answer is old.
     *
     * The broadcast is the fast path and this is the floor under it. Registering
     * a receiver can fail, an OEM can decline to deliver it, and either way the
     * failure is silent and permanent: a browser installed afterwards is not a
     * browser as far as Bastion is concerned until Guard is switched off and on
     * again, which nobody does. Recomputing on a miss, at most once every ten
     * minutes, means the worst case is a short window rather than forever.
     *
     * Only on the miss path, so the ordinary case — a known browser, or an app
     * that is obviously not one — costs a set lookup and nothing else.
     */
    private fun canShowPage(pkg: String): Boolean {
        if (pkg in webCapableApps) return true
        if (System.currentTimeMillis() - lastBrowserScan < BROWSER_RESCAN_MS) return false
        refreshBrowsers()
        return pkg in webCapableApps
    }

    private val packagesChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            refreshBrowsers()
        }
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
            // Mirrored in memory as it is written, so a budget policy sees the
            // same number the database holds without waiting for a read.
            usageToday = usageToday + (pkg to (existing?.foregroundMillis ?: 0L) + elapsed)
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

        // Learn Mode watches. It does not stand in for the guard.
        //
        // Two things were wrong with this, and both were found by using it.
        //
        // It used to `return` here, so arming Learn Mode switched off every
        // block on the phone until the service was restarted. Nothing said so.
        // A man who opened it to report a feed slipping through, and then put
        // his phone down, was left with no guard at all -- and anyone who
        // noticed had a one-tap bypass with no cooling-off attached to it.
        // There is no conflict to trade away here: Learn Mode is for a screen
        // that is *not* being blocked, so on the screen it exists for there is
        // nothing to suppress, and on any other screen blocking is right.
        //
        // And it captured every screen, Bastion's own included. The
        // instruction reads "open that feed, come back here, and pick it from
        // the list" -- so coming back overwrote the capture with the sheet that
        // was about to display it, and the answer was always about Bastion or
        // about the launcher passed through on the way. Skipping our own
        // package makes the instruction true.
        if (learnMode.value) {
            if (learnArmedAt == 0L) learnArmedAt = now
            if (now - learnArmedAt > LEARN_MODE_MAX_MS) {
                // Disarmed on its own. It costs a tree walk per scan in every
                // app, which is worth paying for a minute and not for a week.
                learnMode.value = false
                learnArmedAt = 0L
            } else if (pkg != packageName) {
                captureViewIds()
            }
        } else if (learnArmedAt != 0L) {
            learnArmedAt = 0L
        }

        val guarded = guardedApps[pkg]
        // Leaving a guarded app has to take the veil with it. This was a bare
        // `?: return`, harmless only for as long as the veil was never shown at
        // all; the moment it works, an unguarded return leaves a translucent
        // sheet over the home screen and every other app with no way to clear it
        // but killing the service.
        if (guarded == null) shield.hideDimVeil()

        // During a lockdown every guarded app is fully closed, whatever its own
        // policies say. A break-glass plan that still let the feed-only apps
        // open would not be worth pressing.
        if (guarded != null && com.bastion.app.guard.lockdown.Lockdown.isActive(settings)) {
            // Seconds below a minute, so a short lockdown does not report "0m
            // left" for its entire duration.
            val left = com.bastion.app.guard.lockdown.Lockdown.remainingSeconds(settings)
            val remaining = when {
                left >= 3600 -> "${left / 3600}h ${(left % 3600) / 60}m"
                left >= 60 -> "${left / 60}m"
                else -> "${left}s"
            }
            blockApp(guarded, "Lockdown. $remaining left.")
            return
        }

        // The whole decision, for every app on the phone.
        //
        // There is no longer a branch here on whether the app is guarded, and
        // that is the point. Guarding an app was doing two unrelated jobs: it
        // said what a man wanted closed, and it decided whether Bastion looked
        // at the screen at all. The second was never something he was choosing —
        // nobody thinks of the Google app as an app to limit; he was trying to
        // stop reels, and reels reached him through a web view he never thought
        // to name. What applies where is now a property of the evidence, and
        // whether it fires is a property of a policy.
        checkPolicies(pkg, guarded)

        // Whatever else was decided. Feed policies close Shorts and leave the
        // watch page alone, which is the right shape for a feed and the wrong
        // shape for this: a full-length video is not a feed, and it is where the
        // thing a man is actually avoiding sits.
        checkWatchTitles(pkg, guarded?.label ?: "this page")

        // Reads the global setting, which is the one the UI actually writes.
        //
        // This asked `guarded.grayscale` — a per-app column with no writer
        // anywhere in the app, so it was false for every row ever created and
        // the veil had never once been shown. Meanwhile the "Temptation
        // dampening" switch, and the grayscale step of the break-glass plan,
        // both wrote a global preference that nothing read.
        if (guarded != null && settings.grayscaleEnabled) shield.showDimVeil()
        else if (guarded != null) shield.hideDimVeil()
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
        // Guarded first, exactly like every other rule. A switch inside
        // YouTube's group says what to do about YouTube; it does not say to
        // start watching a service nobody asked about.
        if (!titleRuleOn || YOUTUBE !in guardedApps) return
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
        val page = pageBoundsIn(root)
        val widthCounts = FeedSurface.addressBarWidthCounts(
            realBrowser = root.packageName?.toString() in browserApps,
            webViewFound = page.height() > 0,
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
                if (isAddressBarNode(node, id, window, page, widthCounts)) {
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
     * The evidence that could possibly apply in this app.
     *
     * Two kinds, and the difference between them is the whole reason the old
     * model kept losing. A signal scoped to a package is a fact about that
     * app's layout and applies only there. A signal with no scope is a fact
     * about a destination — an address — and applies wherever an address can be
     * read. There is no list of browsers here, and no question about which
     * window this is, because neither was ever the right question.
     *
     * Being in scope is not the same as being live: whether anything happens is
     * decided afterwards, by the policies, in [PolicyEngine].
     */
    private fun signalsFor(pkg: String): List<SignalEntity> =
        signals.filter { it.scopePackage == null || it.scopePackage == pkg }

    /**
     * One decision, for every app, guarded or not.
     *
     * This replaces two nearly identical methods that existed only because the
     * old model could not say what it meant. `checkFeed` ran the rules an app
     * owned; `checkUniversalFeed` ran the address rules in apps nobody had
     * guarded, because addresses had escaped their packages and there was
     * nowhere honest to file them. Both walked the same tree, kept the same
     * streak and drew the same shield, and the difference between them was a
     * schema apology.
     *
     * Now scope is a property of the evidence and liveness is a property of a
     * policy, so there is one path: gather what could apply here, see what is
     * on screen, and ask the engine.
     */
    private fun checkPolicies(pkg: String, guarded: GuardedAppEntity?) {
        val inScope = signalsFor(pkg)
        val hasScoped = inScope.any { it.scopePackage == pkg }
        val hasAppPolicy = policies.any {
            it.targetType == TargetType.APP && it.targetKey == pkg
        }

        // The cost gate, and the only reason it exists: an unscoped signal
        // applies anywhere, and "anywhere" includes every app a man opens all
        // day. Walking all of their trees to look for an address bar would be a
        // battery complaint. So an app with nothing of its own is walked only
        // when the phone says it can show a page.
        val worthWalking = hasScoped || hasAppPolicy ||
            (inScope.isNotEmpty() && canShowPage(pkg))

        val root = if (worthWalking) rootInActiveWindow else null
        val present = if (root == null) emptyList() else findSurfaces(root, inScope)

        val decision = PolicyEngine.decide(
            present = present,
            policies = policies,
            surfaces = surfacesById,
            categories = categoriesByKey,
            ctx = contextFor(pkg),
        )

        if (decision == null || !decision.blocks) {
            feedHitStreak = 0
            // Watch takes nothing away and still has something to say: it is
            // the honest way to learn what a man's evenings look like before
            // anything is taken from him.
            if (decision != null) record(pkg, decision, Outcome.ARRIVED)
            return
        }

        if (decision.surfaceId == null) {
            // A whole-app decision. Immediate, as it has always been: there is
            // no frame of a closed app to arrive by accident.
            enforceWholeApp(pkg, decision, guarded)
            return
        }

        // Two consecutive scans before acting, ~350ms apart.
        //
        // A reel unit flying past during a fling on the home feed can put a
        // matching node in the tree for a single frame. One scan is enough to
        // catch that and throw a man out of a feed he was allowed to be in;
        // requiring the evidence to still be there on the next scan costs a
        // third of a second on a true positive and removes the whole class of
        // false ones. The streak resets the instant a scan comes back negative.
        feedHitStreak++
        if (feedHitStreak < REQUIRED_FEED_HITS) return
        enforceSurface(pkg, decision, guarded)
    }

    /**
     * Everything the engine is allowed to know, gathered before it is asked.
     *
     * Risk is zero until the risk model ships. It is passed rather than omitted
     * so that the day it becomes real, nothing here has to change shape.
     */
    private fun contextFor(pkg: String) = PolicyContext(
        packageName = pkg,
        minuteOfDay = LocalTime.now().let { it.hour * 60 + it.minute },
        isoDayOfWeek = LocalDate.now().dayOfWeek.value,
        usedMillis = usageToday,
        risk = 0,
    )

    /** Home, then the reason. The app is closed; there is no part of it left. */
    private fun enforceWholeApp(pkg: String, decision: Decision, guarded: GuardedAppEntity?) {
        if (System.currentTimeMillis() - lastInterruptAt < INTERRUPT_COOLDOWN_MS) return
        lastInterruptAt = System.currentTimeMillis()
        record(pkg, decision, Outcome.CLOSED)

        performGlobalAction(GLOBAL_ACTION_HOME)
        shield.show(
            title = guarded?.label ?: "Closed",
            message = decision.reason,
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
     * Back out of the destination, then explain. Order matters: he should
     * already be out before he reads anything.
     */
    private fun enforceSurface(pkg: String, decision: Decision, guarded: GuardedAppEntity?) {
        if (System.currentTimeMillis() - lastInterruptAt < INTERRUPT_COOLDOWN_MS) return
        lastInterruptAt = System.currentTimeMillis()
        feedHitStreak = 0
        record(pkg, decision, Outcome.CLOSED)

        performGlobalAction(GLOBAL_ACTION_BACK)
        // The best loop in the app: it catches the scroll impulse and hands it
        // somewhere good in the same gesture, rather than only saying no and
        // leaving a man holding the urge with nowhere to put it. Taking a feed
        // away and offering nothing back is most of why blockers get deleted.
        shield.show(
            title = "Not this.",
            message = guarded?.let {
                "The rest of ${it.label} is still yours — or scroll something that builds you."
            } ?: "That feed is closed wherever you open it. The rest of the page is still yours.",
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
     * The receipt, and the drift clock.
     *
     * Two writes on a path that already runs at human speed rather than scroll
     * speed. The event is what lets a shield answer "why did this close?"; the
     * timestamp on the signal is what lets Bastion notice, weeks later, that
     * this piece of evidence has stopped firing while the app it watches is
     * opened every day.
     */
    private fun record(pkg: String, decision: Decision, outcome: Outcome) {
        scope.launch {
            runCatching {
                graph.policy.record(
                    packageName = pkg,
                    outcome = outcome,
                    response = decision.response,
                    policyId = decision.policyId,
                    surfaceId = decision.surfaceId,
                    signalId = decision.signalId,
                )
                decision.signalId?.let { graph.policy.markMatched(it) }
            }
        }
    }

    /**
     * Every surface recognised on this screen, and the evidence for each.
     *
     * Bounded breadth-first walk. Bounded on purpose: an unbounded tree walk on
     * every content-changed event is how a guard app becomes a battery
     * complaint.
     *
     * It collects rather than returning on the first hit, because the engine
     * above it is allowed to see more than one destination at a time and
     * choose. One surface per id: a second piece of evidence for something
     * already recognised adds nothing to a decision.
     *
     * Every geometry gate below is untouched — `coversWindow`, the vertical
     * pager test, the address-bar tests. Those were won one false positive at a
     * time and they are inputs to the new pipeline, not casualties of it.
     */
    private fun findSurfaces(
        root: AccessibilityNodeInfo,
        rules: List<SignalEntity>,
    ): List<ResolvedSurface> {
        val found = LinkedHashMap<String, ResolvedSurface>()
        val window = windowBoundsOf(root)
        // Only when a URL rule could fire. An ordinary app with view-id rules
        // has no web view and should not pay for a walk looking for one.
        val page =
            if (rules.any { it.matchType == MatchType.URL }) pageBoundsIn(root)
            else android.graphics.Rect()
        // See FeedSurface.addressBarWidthCounts. Outside a real browser the
        // width guess would fire on a link somebody sent.
        val widthCounts = FeedSurface.addressBarWidthCounts(
            realBrowser = root.packageName?.toString() in browserApps,
            webViewFound = page.height() > 0,
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
                        // Never here either, and for the opposite reason to
                        // TITLE: a package is not something a node can be. The
                        // app itself being the destination is settled before
                        // any tree is walked, so a walk that tried to answer it
                        // would either always say no or match every node on the
                        // screen at once.
                        MatchType.PACKAGE -> false
                        MatchType.URL ->
                            isAddressBarNode(node, idSegment, window, page, widthCounts) &&
                                node.text?.toString()
                                    ?.let { FeedSurface.urlMatches(it, rule.matchValue) } == true
                    }
                    if (hit && !found.containsKey(rule.surfaceId)) {
                        found[rule.surfaceId] = ResolvedSurface(
                            surfaceId = rule.surfaceId,
                            signalId = rule.id,
                            confidence = Confidence.ofOrdinal(rule.confidence),
                        )
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let {
                        queue.add(it)
                        borrowed.add(it)
                    }
                }
            }
            return found.values.toList()
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
        page: android.graphics.Rect,
        widthCounts: Boolean,
    ): Boolean {
        val text = node.text?.toString() ?: return false
        if (!FeedSurface.looksLikeUrl(text)) return false
        if (idSegment != null && idSegment in ADDRESS_BAR_IDS) return true

        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        // Outside the page is the frame the host app drew, whatever it named
        // the label or however small it made it. This is the one that catches
        // the in-app browsers; the width test below only ever caught real ones.
        if (FeedSurface.isBrowserChrome(bounds.top, bounds.bottom, page.top, page.bottom)) {
            return true
        }
        if (!widthCounts) return false

        return FeedSurface.isAddressBar(
            top = bounds.top,
            bottom = bounds.bottom,
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
        page: android.graphics.Rect,
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
                    val byChrome = FeedSurface.isBrowserChrome(
                        bounds.top, bounds.bottom, page.top, page.bottom,
                    )
                    val byWidth = FeedSurface.isAddressBar(
                        top = bounds.top,
                        bottom = bounds.bottom,
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
                            byChrome -> "outside the page"
                            byWidth -> "a wide bar at one end"
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
     * Where the page is drawn, so the frame around it can be told apart.
     *
     * A rectangle rather than an edge, because a toolbar can be above the page
     * or below it and both are the frame. Empty when nothing was found, which
     * every caller reads as "cannot say".
     *
     * Its own bounded walk, run only when a URL rule is actually in play, so
     * the common case of a feed rule in an ordinary app pays nothing for it.
     *
     * ## Not only android.webkit.WebView
     *
     * Matching on the class name containing "WebView" covers Chrome and every
     * Chromium fork, and misses every Firefox one - Focus, Mull, Fennec and
     * IronFox all render through GeckoView, which has no WebView anywhere in
     * its name. In those browsers no page was ever found, so the chrome test
     * could not speak and an address had to be recognised by width and
     * position alone. Naming the engines as well as the class keeps this from
     * being one more list of specific apps: an engine view announces itself,
     * whoever wrapped it.
     */
    private fun pageBoundsIn(root: AccessibilityNodeInfo): android.graphics.Rect {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val borrowed = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                val cls = node.className?.toString().orEmpty()
                val id = node.viewIdResourceName?.substringAfterLast('/')
                val isPage = cls.contains("WebView", ignoreCase = true) ||
                    cls.contains("GeckoView", ignoreCase = true) ||
                    (id != null && id in ENGINE_VIEW_IDS)
                if (isPage) {
                    return android.graphics.Rect().also { node.getBoundsInScreen(it) }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it); borrowed.add(it) }
                }
            }
            return android.graphics.Rect()
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
        // The evidence that could apply here, and — separately — whether
        // anything would actually happen. Learn Mode used to answer only the
        // first, which is how a captured rule could look right and silently
        // never fire.
        val inScope = signalsFor(pkg)

        // The browser diagnosis, which exists because four attempts at the
        // in-app-browser path were made without ever seeing what the service
        // actually had in front of it. Guessing from a laptop is how a fix ships
        // that cannot work; this turns "still not working" into a sentence
        // naming the link that is broken.
        val page = pageBoundsIn(root)
        val addresses = if (inScope.any { it.matchType == MatchType.URL }) {
            seenAddresses(root, windowBoundsOf(root), page)
        } else emptyList()

        learnedIds.value = LearnCapture(
            packageName = pkg,
            // Ids that would actually block sort first; there are usually one
            // or two among a hundred.
            viewIds = found.entries
                .map { LearnedId(it.key, it.value) }
                .sortedByDescending { it.wouldBlock },
            // The live verdict, and it now asks the engine rather than the
            // matcher. "Does a rule match this screen" and "would anything
            // happen" are different questions, and the gap between them is
            // exactly where a man loses an evening: evidence that matches
            // perfectly, under a policy that is switched off or out of hours,
            // looks identical to protection until it is needed.
            blockedNow = PolicyEngine.decide(
                present = findSurfaces(root, inScope),
                policies = policies,
                surfaces = surfacesById,
                categories = categoriesByKey,
                ctx = contextFor(pkg),
            )?.blocks == true,
            guardedAs = guardedApps[pkg]?.mode?.name,
            ruleCount = inScope.size,
            urlRuleCount = inScope.count { it.matchType == MatchType.URL },
            webViewFound = page.height() > 0,
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
        // Registered in onServiceConnected; leaking it would log a warning on
        // every rebind and hold a reference to a dead service.
        runCatching { unregisterReceiver(packagesChanged) }
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
            // Firefox Focus, Opera, and the Chromium forks that renamed it.
            // Consulted only for a node whose text already parses as a URL, so
            // a broad name here cannot claim something that is not an address.
            "display_url",
            "url_field",
            "urlbar",
            "address_bar",
        )

        /**
         * A page, by the name its renderer gives itself.
         *
         * Class name catches WebView and GeckoView; these catch the wrappers
         * that expose the engine under an identifier of their own.
         */
        private val ENGINE_VIEW_IDS = setOf(
            "engineView",
            "mozac_browser_engineView",
            "webview",
            "web_view",
            "browser_view",
        )

        /** How stale the browser list may get before a miss pays to refresh it. */
        private const val BROWSER_RESCAN_MS = 10 * 60 * 1000L

        /** How long Learn Mode stays armed. Capturing a screen takes seconds. */
        private const val LEARN_MODE_MAX_MS = 10 * 60 * 1000L

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
        /** The service the title rule belongs to; see checkWatchTitles. */
        private const val YOUTUBE = "com.google.android.youtube"

        private val WATCH_APPS = setOf(
            YOUTUBE,
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
