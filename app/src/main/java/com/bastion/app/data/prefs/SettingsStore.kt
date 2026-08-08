package com.bastion.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bastion_settings")

data class Settings(
    val faithMode: Boolean = true,
    val onboarded: Boolean = false,
    val name: String = "",
    val journeyStartEpochDay: Long = 0L,
    val briefHour: Int = 7,
    val briefMinute: Int = 0,
    val briefEnabled: Boolean = true,
    val vpnFilterEnabled: Boolean = false,
    val grayscaleEnabled: Boolean = false,
    /**
     * Weakening a guard waits this many minutes before it takes effect.
     *
     * Minutes rather than hours because the shortest honest delay a man might
     * want is not a whole hour — and because a five-minute setting exists so
     * the wall and the unlock flow can actually be exercised without waiting
     * out a real one. Stored under a new key; see [Settings.coolingOffMinutes]
     * in SettingsStore.read for how an existing hours value is carried over.
     */
    val coolingOffMinutes: Int = 120,
    val tamperLockEnabled: Boolean = false,
    val partnerLockEnabled: Boolean = false,
    /** Consecutive wrong partner-code entries; reset by a correct one. */
    val passcodeFailures: Int = 0,
    /** Epoch millis until which no code may be tried at all. 0 when free. */
    val passcodeLockedUntil: Long = 0L,
    val triggers: List<String> = emptyList(),
    val baselineFrequency: String = "",
    val lastPanicAt: Long = 0L,
    val panicCount: Int = 0,
    val upstreamDns: String = "1.1.1.3",
    /**
     * Where Bastion looks for a newer build — the manifest attached to the
     * latest GitHub release. Pre-filled, but nothing is fetched until you press
     * Check, and [autoCheckUpdates] stays off until you turn it on: the app
     * still makes no network calls of its own accord.
     */
    val updateManifestUrl: String = com.bastion.app.BuildConfig.DEFAULT_UPDATE_URL,
    val autoCheckUpdates: Boolean = false,
    val lastUpdateCheck: Long = 0L,
    /**
     * Whether the Mentor has already greeted this user.
     *
     * Must be persisted rather than inferred from the message list: that list
     * is empty for the first frames of every launch, so greeting on "history is
     * empty" posted a fresh opener over real history each time.
     */
    val mentorOpenerSent: Boolean = false,
    /**
     * The motivation line shown last time the urge screen opened.
     *
     * Persisted rather than held in memory: the panic screen is a fresh
     * activity every time, so an in-memory guard would let the same words
     * greet a man twice running — which is exactly when he stops reading them.
     */
    val lastUrgeItemId: String = "",
    /**
     * Lines from the library he has kept.
     *
     * Ids only, in DataStore rather than the database: the items themselves
     * are bundled read-only content, so the only thing worth persisting is
     * which of them mattered to him — and that needs no migration.
     */
    val savedMotivation: List<String> = emptyList(),
    /** Guards seed data from returning after a deliberate clear-out. */
    val guardSeeded: Boolean = false,
    /**
     * Which generation of built-in feed rules this install has been offered.
     *
     * Not a re-seed flag. Rules added in a later version are inserted once, by
     * id, and rules the user has since deleted are never resurrected — they
     * belong to a generation already offered, and undoing a decision a man made
     * is exactly what the seeding flag above exists to prevent.
     */
    val builtInRulesVersion: Int = 0,
    /**
     * Highest rank the user has actually been shown.
     *
     * Defaults to 1 so that simply being a Recruit — the starting state, not an
     * achievement — never triggers the ceremony.
     */
    val lastSeenRankTier: Int = 1,
    /**
     * Whether the user has ever had Bastion Guard switched on.
     *
     * The service cannot report its own death — once it is disabled in system
     * settings it simply stops existing. Recording the intent is what lets
     * Bastion notice the gap between what was asked for and what is running.
     */
    val guardIntendedOn: Boolean = false,
    /** So the nag is a reminder, not a pestering. */
    val guardOffNotifiedAt: Long = 0L,

    /**
     * Whether the user has ever had Private DNS pointed at a hostname.
     *
     * The same trick as [guardIntendedOn], for the same reason: Private DNS is
     * a system setting Bastion can neither switch on nor hold down, so the only
     * way to notice it being turned off is to have recorded that it was once
     * on. Set the first time it is seen set, so nobody has to declare the
     * intent separately from doing the thing.
     */
    val dnsIntendedOn: Boolean = false,
    /** The hostname it was set to, so the app can name it when it disappears. */
    val dnsHostname: String = "",
    /** Epoch millis Private DNS was first seen off. 0 while it is set. */
    val dnsOffSince: Long = 0L,
    /**
     * Epoch millis Guard was first seen missing. 0 while it is running.
     *
     * Without this a breach had no duration — every notice said the same thing
     * whether Guard had been off for a minute or since Tuesday, and the partner
     * message could not tell him which of those it was. It is set once, on the
     * first reconcile that finds Guard gone, and cleared the moment it returns.
     */
    val guardOffSince: Long = 0L,
    /** Guards against re-sending the lockdown-breach alert for one breach. */
    val lockdownBreachAlerted: Boolean = false,

    // --- Lockdown: the break-glass plan ---------------------------------
    /** How long a lockdown lasts once triggered. */
    /**
     * Seconds, not hours.
     *
     * It was hours, which made the shortest possible lockdown an hour — long
     * enough that nobody could reasonably check the thing worked before
     * trusting it with a real evening. A plan you cannot rehearse is a plan you
     * find out about at the worst moment.
     */
    val lockdownSeconds: Int = 24 * 60 * 60,
    /** Epoch millis the current lockdown ends. 0 when none is running. */
    val lockdownUntil: Long = 0L,
    /** Monotonic twin of [lockdownUntil]; the user cannot move this one. */
    val lockdownEndElapsed: Long = 0L,
    /**
     * How long the lockdown that is currently running was asked for.
     *
     * Not the same thing as [lockdownSeconds], which is only what the *button*
     * would ask for next time. The two parted company when the nightly schedule
     * arrived with a length of its own: a one-hour scheduled lockout running
     * against a 30-second button setting would have had its monotonic clock
     * thrown away as nonsense, because that check measures the remainder against
     * the lockdown's own length. 0 means "never recorded", and the old field is
     * used instead — which is exactly the pre-schedule behaviour.
     */
    val lockdownSpanSeconds: Int = 0,
    val lockdownLockScreen: Boolean = true,
    val lockdownFilter: Boolean = true,
    val lockdownGrayscale: Boolean = true,
    val lockdownTellPartner: Boolean = true,

    // --- Lockdown: the same plan, on a clock --------------------------------
    //
    // The button is for the bad moment that arrives unannounced. This is for the
    // one that arrives every night at the same time — and which therefore does
    // not need a man to be watching for it, only to have decided about it once.
    /** Whether the nightly lockout runs at all. */
    val scheduledLockdownEnabled: Boolean = false,
    /** When it starts, on a 24-hour clock. Defaults to the hour most people mean. */
    val scheduledLockdownHour: Int = 22,
    val scheduledLockdownMinute: Int = 0,
    /**
     * How long it holds, in seconds — its own length, not the button's.
     *
     * A panic lockdown defaults to a day because the moment it answers is a
     * crisis. A nightly one that defaulted to a day would swallow the next day
     * whole, and the man who set it would only find that out once.
     */
    val scheduledLockdownSeconds: Int = 60 * 60,
    /**
     * Epoch millis of the last window this schedule has settled — served, or
     * deliberately skipped because the user was still setting it up inside one.
     *
     * The whole reason the catch-up after a reboot can tell a missed night from
     * a night already handled. 0 means it has never run.
     */
    val scheduledLockdownLastRun: Long = 0L,
    /**
     * Which days it runs, as ISO weekday numbers. Empty means every day.
     *
     * It was called a nightly lockdown and it only ever was one — the same hour,
     * seven days a week. A man whose hard night is Friday and Saturday had to
     * choose between a curfew he did not want on a Tuesday and no curfew at all.
     */
    val curfewDaysCsv: String = "",
    /**
     * The curfew's own plan, rather than the break-glass button's.
     *
     * Sharing one plan was deliberate — two that could drift apart is two things
     * to keep true — but it made the curfew unconfigurable: changing what it did
     * changed what the panic button did, which is a different decision entirely.
     * A curfew is a routine and a panic press is a crisis, and they do not want
     * the same response.
     */
    val curfewLockScreen: Boolean = true,
    val curfewFilter: Boolean = true,
    val curfewGrayscale: Boolean = true,
    /**
     * Off by default, unlike the button's.
     *
     * Telling a partner about one bad moment is the point. Texting him the same
     * message at ten o'clock every night is noise he will learn to ignore, and
     * the message that actually matters arrives inside that noise.
     */
    val curfewTellPartner: Boolean = false,
) {
    /** The chosen days, parsed. Empty means every day. */
    val curfewDays: List<Int>
        get() = curfewDaysCsv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
}

/**
 * Small, non-relational state: the knobs, not the confessions.
 *
 * What follows is deliberately precise, because the previous version of this
 * comment implied the database provided encryption and it does not.
 *
 * Encrypted at rest:
 *   - the covenant signature, via [com.bastion.app.core.security.CovenantVault]
 *     and an Android Keystore key.
 *
 * NOT encrypted, and honest about it:
 *   - `bastion.db` is plain SQLite. The covenant text, slip history, urge logs
 *     and mentor conversation are readable by anything that can reach the
 *     filesystem — a rooted device, a recovery image, a forensic dump.
 *   - the "why" video, which the camera app writes directly.
 *   - this preferences file.
 *
 * They are protected by app-private storage and by `allowBackup=false`, which
 * defeats other apps and Google's cloud backup but not physical access. Full
 * database encryption means SQLCipher and a careful re-key of any existing
 * install; it is worth doing, and it is not worth doing carelessly, because a
 * botched migration would destroy exactly the journey it is meant to guard.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val FAITH_MODE = booleanPreferencesKey("faith_mode")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val NAME = stringPreferencesKey("name")
        val JOURNEY_START = longPreferencesKey("journey_start")
        val BRIEF_HOUR = intPreferencesKey("brief_hour")
        val BRIEF_MINUTE = intPreferencesKey("brief_minute")
        val BRIEF_ENABLED = booleanPreferencesKey("brief_enabled")
        val VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
        val GRAYSCALE = booleanPreferencesKey("grayscale")
        val COOLING_OFF = intPreferencesKey("cooling_off_hours")
        val COOLING_OFF_MINUTES = intPreferencesKey("cooling_off_minutes")
        val TAMPER_LOCK = booleanPreferencesKey("tamper_lock")
        val PARTNER_LOCK = booleanPreferencesKey("partner_lock")
        val GUARD_OFF_SINCE = longPreferencesKey("guard_off_since")
        val DNS_INTENDED_ON = booleanPreferencesKey("dns_intended_on")
        val DNS_HOSTNAME = stringPreferencesKey("dns_hostname")
        val DNS_OFF_SINCE = longPreferencesKey("dns_off_since")
        val LOCKDOWN_BREACH_ALERTED = booleanPreferencesKey("lockdown_breach_alerted")
        val PASSCODE_FAILURES = intPreferencesKey("passcode_failures")
        val PASSCODE_LOCKED_UNTIL = longPreferencesKey("passcode_locked_until")
        val TRIGGERS = stringPreferencesKey("triggers")
        val BASELINE = stringPreferencesKey("baseline_frequency")
        val LAST_PANIC = longPreferencesKey("last_panic")
        val PANIC_COUNT = intPreferencesKey("panic_count")
        val UPSTREAM_DNS = stringPreferencesKey("upstream_dns")
        val UPDATE_URL = stringPreferencesKey("update_url")
        val AUTO_CHECK = booleanPreferencesKey("auto_check_updates")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val MENTOR_OPENER_SENT = booleanPreferencesKey("mentor_opener_sent")
        val GUARD_SEEDED = booleanPreferencesKey("guard_seeded")
        val BUILTIN_RULES_VERSION = intPreferencesKey("builtin_rules_version")
        val LAST_URGE_ITEM = stringPreferencesKey("last_urge_item")
        val SAVED_MOTIVATION = stringPreferencesKey("saved_motivation")
        val LAST_SEEN_RANK = intPreferencesKey("last_seen_rank_tier")
        val GUARD_INTENDED_ON = booleanPreferencesKey("guard_intended_on")
        val GUARD_OFF_NOTIFIED = longPreferencesKey("guard_off_notified_at")
        val LOCKDOWN_HOURS = intPreferencesKey("lockdown_hours")
        val LOCKDOWN_SECONDS = intPreferencesKey("lockdown_seconds")
        val LOCKDOWN_UNTIL = longPreferencesKey("lockdown_until")
        val LOCKDOWN_END_ELAPSED = longPreferencesKey("lockdown_end_elapsed")
        val LOCKDOWN_SPAN = intPreferencesKey("lockdown_span_seconds")
        val LOCKDOWN_LOCK_SCREEN = booleanPreferencesKey("lockdown_lock_screen")
        val LOCKDOWN_FILTER = booleanPreferencesKey("lockdown_filter")
        val LOCKDOWN_GRAYSCALE = booleanPreferencesKey("lockdown_grayscale")
        val LOCKDOWN_PARTNER = booleanPreferencesKey("lockdown_partner")
        val SCHEDULED_LOCKDOWN = booleanPreferencesKey("scheduled_lockdown_enabled")
        val SCHEDULED_LOCKDOWN_HOUR = intPreferencesKey("scheduled_lockdown_hour")
        val SCHEDULED_LOCKDOWN_MINUTE = intPreferencesKey("scheduled_lockdown_minute")
        val SCHEDULED_LOCKDOWN_SECONDS = intPreferencesKey("scheduled_lockdown_seconds")
        val SCHEDULED_LOCKDOWN_LAST_RUN = longPreferencesKey("scheduled_lockdown_last_run")
        val CURFEW_DAYS = stringPreferencesKey("curfew_days")
        val CURFEW_LOCK_SCREEN = booleanPreferencesKey("curfew_lock_screen")
        val CURFEW_FILTER = booleanPreferencesKey("curfew_filter")
        val CURFEW_GRAYSCALE = booleanPreferencesKey("curfew_grayscale")
        val CURFEW_TELL_PARTNER = booleanPreferencesKey("curfew_tell_partner")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            faithMode = p[Keys.FAITH_MODE] ?: true,
            onboarded = p[Keys.ONBOARDED] ?: false,
            name = p[Keys.NAME] ?: "",
            journeyStartEpochDay = p[Keys.JOURNEY_START] ?: 0L,
            briefHour = p[Keys.BRIEF_HOUR] ?: 7,
            briefMinute = p[Keys.BRIEF_MINUTE] ?: 0,
            briefEnabled = p[Keys.BRIEF_ENABLED] ?: true,
            vpnFilterEnabled = p[Keys.VPN_ENABLED] ?: false,
            grayscaleEnabled = p[Keys.GRAYSCALE] ?: false,
            // Minutes if they have been set, otherwise the old hours value
            // converted, otherwise the default. Reading the legacy key rather
            // than migrating it in place means a man who never touches the
            // setting keeps exactly the delay he had, and nobody's lock gets
            // quietly shortened by an upgrade.
            coolingOffMinutes = p[Keys.COOLING_OFF_MINUTES]
                ?: p[Keys.COOLING_OFF]?.let { it * 60 }
                ?: 120,
            tamperLockEnabled = p[Keys.TAMPER_LOCK] ?: false,
            partnerLockEnabled = p[Keys.PARTNER_LOCK] ?: false,
            guardOffSince = p[Keys.GUARD_OFF_SINCE] ?: 0L,
            dnsIntendedOn = p[Keys.DNS_INTENDED_ON] ?: false,
            dnsHostname = p[Keys.DNS_HOSTNAME] ?: "",
            dnsOffSince = p[Keys.DNS_OFF_SINCE] ?: 0L,
            lockdownBreachAlerted = p[Keys.LOCKDOWN_BREACH_ALERTED] ?: false,
            passcodeFailures = p[Keys.PASSCODE_FAILURES] ?: 0,
            passcodeLockedUntil = p[Keys.PASSCODE_LOCKED_UNTIL] ?: 0L,
            triggers = p[Keys.TRIGGERS].decodeTriggers(),
            baselineFrequency = p[Keys.BASELINE] ?: "",
            lastPanicAt = p[Keys.LAST_PANIC] ?: 0L,
            panicCount = p[Keys.PANIC_COUNT] ?: 0,
            upstreamDns = p[Keys.UPSTREAM_DNS] ?: "1.1.1.3",
            updateManifestUrl = p[Keys.UPDATE_URL] ?: com.bastion.app.BuildConfig.DEFAULT_UPDATE_URL,
            autoCheckUpdates = p[Keys.AUTO_CHECK] ?: false,
            lastUpdateCheck = p[Keys.LAST_UPDATE_CHECK] ?: 0L,
            mentorOpenerSent = p[Keys.MENTOR_OPENER_SENT] ?: false,
            guardSeeded = p[Keys.GUARD_SEEDED] ?: false,
            builtInRulesVersion = p[Keys.BUILTIN_RULES_VERSION] ?: 0,
            lastUrgeItemId = p[Keys.LAST_URGE_ITEM] ?: "",
            savedMotivation = p[Keys.SAVED_MOTIVATION].decodeTriggers(),
            lastSeenRankTier = p[Keys.LAST_SEEN_RANK] ?: 1,
            guardIntendedOn = p[Keys.GUARD_INTENDED_ON] ?: false,
            guardOffNotifiedAt = p[Keys.GUARD_OFF_NOTIFIED] ?: 0L,
            // Falls back to the old hours key so an existing install keeps
            // the duration its owner chose rather than silently resetting to
            // the default the day this shipped.
            lockdownSeconds = p[Keys.LOCKDOWN_SECONDS]
                ?: p[Keys.LOCKDOWN_HOURS]?.times(60 * 60)
                ?: (24 * 60 * 60),
            lockdownUntil = p[Keys.LOCKDOWN_UNTIL] ?: 0L,
            lockdownEndElapsed = p[Keys.LOCKDOWN_END_ELAPSED] ?: 0L,
            lockdownSpanSeconds = p[Keys.LOCKDOWN_SPAN] ?: 0,
            lockdownLockScreen = p[Keys.LOCKDOWN_LOCK_SCREEN] ?: true,
            lockdownFilter = p[Keys.LOCKDOWN_FILTER] ?: true,
            lockdownGrayscale = p[Keys.LOCKDOWN_GRAYSCALE] ?: true,
            lockdownTellPartner = p[Keys.LOCKDOWN_PARTNER] ?: true,
            scheduledLockdownEnabled = p[Keys.SCHEDULED_LOCKDOWN] ?: false,
            scheduledLockdownHour = p[Keys.SCHEDULED_LOCKDOWN_HOUR] ?: 22,
            scheduledLockdownMinute = p[Keys.SCHEDULED_LOCKDOWN_MINUTE] ?: 0,
            scheduledLockdownSeconds = p[Keys.SCHEDULED_LOCKDOWN_SECONDS] ?: (60 * 60),
            scheduledLockdownLastRun = p[Keys.SCHEDULED_LOCKDOWN_LAST_RUN] ?: 0L,
            curfewDaysCsv = p[Keys.CURFEW_DAYS] ?: "",
            curfewLockScreen = p[Keys.CURFEW_LOCK_SCREEN] ?: true,
            curfewFilter = p[Keys.CURFEW_FILTER] ?: true,
            curfewGrayscale = p[Keys.CURFEW_GRAYSCALE] ?: true,
            curfewTellPartner = p[Keys.CURFEW_TELL_PARTNER] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setFaithMode(value: Boolean) = edit { it[Keys.FAITH_MODE] = value }
    suspend fun setOnboarded(value: Boolean) = edit { it[Keys.ONBOARDED] = value }
    suspend fun setName(value: String) = edit { it[Keys.NAME] = value }
    suspend fun setJourneyStart(epochDay: Long) = edit { it[Keys.JOURNEY_START] = epochDay }
    suspend fun setBriefTime(hour: Int, minute: Int) = edit {
        it[Keys.BRIEF_HOUR] = hour
        it[Keys.BRIEF_MINUTE] = minute
    }
    suspend fun setBriefEnabled(value: Boolean) = edit { it[Keys.BRIEF_ENABLED] = value }
    suspend fun setVpnEnabled(value: Boolean) = edit { it[Keys.VPN_ENABLED] = value }
    suspend fun setGrayscale(value: Boolean) = edit { it[Keys.GRAYSCALE] = value }
    /**
     * Writes both keys, so the value survives a downgrade.
     *
     * Bastion updates in place and a man can end up on an older build; if that
     * build read a stale hours key it would silently restore a delay he had
     * already changed. The legacy key is kept truthful, rounded up so a
     * sub-hour delay never becomes zero hours there.
     */
    suspend fun setCoolingOffMinutes(value: Int) = edit {
        it[Keys.COOLING_OFF_MINUTES] = value
        it[Keys.COOLING_OFF] = ((value + 59) / 60).coerceAtLeast(1)
    }
    suspend fun setTamperLock(value: Boolean) = edit { it[Keys.TAMPER_LOCK] = value }
    suspend fun setPartnerLock(value: Boolean) = edit { it[Keys.PARTNER_LOCK] = value }
    suspend fun setGuardOffSince(value: Long) = edit { it[Keys.GUARD_OFF_SINCE] = value }
    suspend fun setDnsIntendedOn(value: Boolean) = edit { it[Keys.DNS_INTENDED_ON] = value }
    suspend fun setDnsHostname(value: String) = edit { it[Keys.DNS_HOSTNAME] = value }
    suspend fun setDnsOffSince(value: Long) = edit { it[Keys.DNS_OFF_SINCE] = value }
    suspend fun setLockdownBreachAlerted(value: Boolean) = edit {
        it[Keys.LOCKDOWN_BREACH_ALERTED] = value
    }
    suspend fun setPasscodeFailures(value: Int) = edit { it[Keys.PASSCODE_FAILURES] = value }
    suspend fun setPasscodeLockedUntil(value: Long) = edit {
        it[Keys.PASSCODE_LOCKED_UNTIL] = value
    }
    suspend fun setTriggers(values: List<String>) = edit {
        it[Keys.TRIGGERS] = Json.encodeToString(values)
    }
    suspend fun setBaseline(value: String) = edit { it[Keys.BASELINE] = value }
    suspend fun setUpstreamDns(value: String) = edit { it[Keys.UPSTREAM_DNS] = value }
    suspend fun setUpdateUrl(value: String) = edit { it[Keys.UPDATE_URL] = value.trim() }
    suspend fun setAutoCheckUpdates(value: Boolean) = edit { it[Keys.AUTO_CHECK] = value }
    suspend fun markUpdateChecked() = edit { it[Keys.LAST_UPDATE_CHECK] = System.currentTimeMillis() }
    suspend fun setMentorOpenerSent(value: Boolean) = edit { it[Keys.MENTOR_OPENER_SENT] = value }
    suspend fun setGuardSeeded(value: Boolean) = edit { it[Keys.GUARD_SEEDED] = value }
    suspend fun setBuiltInRulesVersion(value: Int) = edit { it[Keys.BUILTIN_RULES_VERSION] = value }
    suspend fun setLastUrgeItem(value: String) = edit { it[Keys.LAST_URGE_ITEM] = value }

    /** Keeps or un-keeps one line. Reuses the trigger list encoding. */
    suspend fun toggleSavedMotivation(id: String) = edit { prefs ->
        val current = prefs[Keys.SAVED_MOTIVATION].decodeTriggers()
        val next = if (id in current) current - id else current + id
        prefs[Keys.SAVED_MOTIVATION] = Json.encodeToString(next)
    }
    suspend fun setLastSeenRankTier(value: Int) = edit { it[Keys.LAST_SEEN_RANK] = value }
    suspend fun setGuardIntendedOn(value: Boolean) = edit { it[Keys.GUARD_INTENDED_ON] = value }
    suspend fun setGuardOffNotifiedAt(value: Long) = edit { it[Keys.GUARD_OFF_NOTIFIED] = value }
    suspend fun setLockdownSeconds(value: Int) = edit { it[Keys.LOCKDOWN_SECONDS] = value }
    suspend fun setLockdownUntil(value: Long) = edit { it[Keys.LOCKDOWN_UNTIL] = value }
    suspend fun setLockdownEndElapsed(value: Long) = edit { it[Keys.LOCKDOWN_END_ELAPSED] = value }
    suspend fun setLockdownSpanSeconds(value: Int) = edit { it[Keys.LOCKDOWN_SPAN] = value }
    suspend fun setLockdownLockScreen(value: Boolean) = edit { it[Keys.LOCKDOWN_LOCK_SCREEN] = value }
    suspend fun setLockdownFilter(value: Boolean) = edit { it[Keys.LOCKDOWN_FILTER] = value }
    suspend fun setLockdownGrayscale(value: Boolean) = edit { it[Keys.LOCKDOWN_GRAYSCALE] = value }
    suspend fun setLockdownTellPartner(value: Boolean) = edit { it[Keys.LOCKDOWN_PARTNER] = value }
    suspend fun setCurfewDays(days: List<Int>) = edit {
        it[Keys.CURFEW_DAYS] = days.sorted().joinToString(",")
    }
    suspend fun setCurfewLockScreen(v: Boolean) = edit { it[Keys.CURFEW_LOCK_SCREEN] = v }
    suspend fun setCurfewFilter(v: Boolean) = edit { it[Keys.CURFEW_FILTER] = v }
    suspend fun setCurfewGrayscale(v: Boolean) = edit { it[Keys.CURFEW_GRAYSCALE] = v }
    suspend fun setCurfewTellPartner(v: Boolean) = edit { it[Keys.CURFEW_TELL_PARTNER] = v }

    suspend fun setScheduledLockdownEnabled(value: Boolean) = edit {
        it[Keys.SCHEDULED_LOCKDOWN] = value
    }
    suspend fun setScheduledLockdownTime(hour: Int, minute: Int) = edit {
        it[Keys.SCHEDULED_LOCKDOWN_HOUR] = hour
        it[Keys.SCHEDULED_LOCKDOWN_MINUTE] = minute
    }
    suspend fun setScheduledLockdownSeconds(value: Int) = edit {
        it[Keys.SCHEDULED_LOCKDOWN_SECONDS] = value
    }
    suspend fun setScheduledLockdownLastRun(value: Long) = edit {
        it[Keys.SCHEDULED_LOCKDOWN_LAST_RUN] = value
    }

    suspend fun recordPanic() = edit {
        it[Keys.LAST_PANIC] = System.currentTimeMillis()
        it[Keys.PANIC_COUNT] = (it[Keys.PANIC_COUNT] ?: 0) + 1
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        /**
         * Triggers were pipe-joined, so a trigger containing "|" silently split
         * into two. JSON round-trips anything, and reads the old pipe format so
         * an existing install does not lose its answers on upgrade.
         */
        fun String?.decodeTriggers(): List<String> {
            if (this.isNullOrBlank()) return emptyList()
            return runCatching { Json.decodeFromString<List<String>>(this) }
                .getOrElse { split('|').filter { part -> part.isNotBlank() } }
        }
    }
}

