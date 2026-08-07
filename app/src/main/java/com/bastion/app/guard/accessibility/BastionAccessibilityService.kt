package com.bastion.app.guard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.FeedRuleEntity
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.MatchType
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
                rulesByPackage = rules.filter { it.enabled }.groupBy { it.packageName }
            }
        }
        scope.launch {
            graph.settings.settings.collect { settings = it }
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
                guardSettingsScreen(pkg, event.className?.toString())
                evaluate(pkg, force = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> evaluate(pkg, force = false)
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
    private fun guardSettingsScreen(pkg: String, className: String?) {
        if (!settings.tamperLockEnabled) return
        if (!GuardedScreens.isSettingsApp(pkg)) return

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
        )

        if (guarded == null) {
            val root = rootInActiveWindow ?: return
            val ids = mutableSetOf<String>()
            val texts = mutableSetOf<String>()
            collectIdentity(root, ids, texts)
            guarded = GuardedScreens.detect(
                packageName = pkg,
                className = className,
                viewIds = ids,
                texts = texts,
                serviceLabel = getString(com.bastion.app.R.string.accessibility_label),
                dnsHostname = settings.dnsHostname,
            )
        }

        if (guarded == null) return
        lastSettingsWallAt = now
        com.bastion.app.guard.lockdown.SettingsWallActivity.raise(this, guarded)
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

    /** The feed surgery: let the app run, close only the screen that hurts. */
    private fun checkFeed(pkg: String, app: GuardedAppEntity) {
        val rules = rulesByPackage[pkg] ?: return
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
            blockedNow = findMatch(root, rulesByPackage[pkg].orEmpty()) != null,
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

    data class LearnCapture(
        val packageName: String,
        val viewIds: List<LearnedId>,
        /** Whether an existing rule already covers the captured screen. */
        val blockedNow: Boolean,
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
        private const val NOTIFICATION_GUARD_DOWN = 4401
        private const val HALF_HOUR = 30 * 60 * 1000L

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
         * Longer than the lockdown wall's, and for the opposite reason.
         *
         * That one wants to be instant and relentless. This one has to leave
         * room for the user to actually leave: raising it again while he is on
         * his way out would make the exit unreachable, which is the cage this
         * screen is written not to be.
         */
        private const val SETTINGS_WALL_COOLDOWN_MS = 2_000L

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
