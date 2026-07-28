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
    /** Weakening a guard waits this many hours before it takes effect. */
    val coolingOffHours: Int = 2,
    val tamperLockEnabled: Boolean = false,
    val partnerLockEnabled: Boolean = false,
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
    /** Guards seed data from returning after a deliberate clear-out. */
    val guardSeeded: Boolean = false,
    /**
     * Highest rank the user has actually been shown.
     *
     * Defaults to 1 so that simply being a Recruit — the starting state, not an
     * achievement — never triggers the ceremony.
     */
    val lastSeenRankTier: Int = 1,
)

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
        val TAMPER_LOCK = booleanPreferencesKey("tamper_lock")
        val PARTNER_LOCK = booleanPreferencesKey("partner_lock")
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
        val LAST_SEEN_RANK = intPreferencesKey("last_seen_rank_tier")
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
            coolingOffHours = p[Keys.COOLING_OFF] ?: 2,
            tamperLockEnabled = p[Keys.TAMPER_LOCK] ?: false,
            partnerLockEnabled = p[Keys.PARTNER_LOCK] ?: false,
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
            lastSeenRankTier = p[Keys.LAST_SEEN_RANK] ?: 1,
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
    suspend fun setCoolingOffHours(value: Int) = edit { it[Keys.COOLING_OFF] = value }
    suspend fun setTamperLock(value: Boolean) = edit { it[Keys.TAMPER_LOCK] = value }
    suspend fun setPartnerLock(value: Boolean) = edit { it[Keys.PARTNER_LOCK] = value }
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
    suspend fun setLastSeenRankTier(value: Int) = edit { it[Keys.LAST_SEEN_RANK] = value }

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
