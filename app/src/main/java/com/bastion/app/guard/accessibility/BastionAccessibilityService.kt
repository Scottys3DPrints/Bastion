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

    private var lastScanAt = 0L
    private var lastInterruptAt = 0L
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg in SYSTEM_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onForegroundChanged(pkg)
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
            if (shield.isShowing) shield.hide()
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

        val guarded = guardedApps[pkg] ?: return

        when (guarded.mode) {
            BlockMode.FULL -> blockApp(guarded, "Closed for now.")
            BlockMode.SCHEDULE -> if (withinWindow(guarded.scheduleStart, guarded.scheduleEnd)) {
                blockApp(guarded, "Protected time.")
            }
            BlockMode.TIME_LIMIT -> checkTimeLimit(guarded)
            BlockMode.FEED_ONLY -> checkFeed(pkg, guarded)
        }

        if (guarded.grayscale) shield.showDimVeil() else shield.hideDimVeil()
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
        if (matched != null) {
            if (System.currentTimeMillis() - lastInterruptAt < INTERRUPT_COOLDOWN_MS) return
            lastInterruptAt = System.currentTimeMillis()

            // Step back out of the feed first, then explain. Order matters: the
            // user should already be out before he reads anything.
            performGlobalAction(GLOBAL_ACTION_BACK)
            shield.show(
                title = "Not this.",
                message = "The rest of ${app.label} is still yours.",
                primaryLabel = "Good call",
                onPrimary = { shield.hide() },
                secondaryLabel = "Hold the line",
                onSecondary = {
                    shield.hide()
                    openPanic()
                },
                autoDismissMillis = 6_000,
            )
        }
    }

    /**
     * Bounded breadth-first walk. Bounded on purpose: an unbounded tree walk on
     * every content-changed event is how a guard app becomes a battery complaint.
     */
    private fun findMatch(root: AccessibilityNodeInfo, rules: List<FeedRuleEntity>): FeedRuleEntity? {
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

                val viewId = node.viewIdResourceName
                for (rule in rules) {
                    val hit = when (rule.matchType) {
                        MatchType.VIEW_ID -> viewId != null && viewId.contains(rule.matchValue, ignoreCase = true)
                        MatchType.CONTENT_DESC -> node.contentDescription.equalsIgnoreCase(rule.matchValue)
                        MatchType.TEXT -> node.text.equalsIgnoreCase(rule.matchValue)
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
        val found = LinkedHashSet<String>()
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
                ?.let { found.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    queue.add(it)
                    borrowed.add(it)
                }
            }
        }
        recycleAll(borrowed)
        learnedIds.value = LearnCapture(
            packageName = root.packageName?.toString().orEmpty(),
            viewIds = found.toList(),
        )
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

    data class LearnCapture(val packageName: String, val viewIds: List<String>)

    companion object {
        private const val SCAN_THROTTLE_MS = 350L
        private const val INTERRUPT_COOLDOWN_MS = 1_800L
        private const val MAX_NODES = 500
        private const val NOTIFICATION_GUARD_DOWN = 4401
        private const val HALF_HOUR = 30 * 60 * 1000L

        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.settings.intelligence",
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
