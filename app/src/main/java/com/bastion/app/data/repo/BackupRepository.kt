package com.bastion.app.data.repo

import com.bastion.app.core.security.BackupCodec
import com.bastion.app.data.db.BackupDao
import com.bastion.app.data.db.BadgeEntity
import com.bastion.app.data.db.BlockedDomainEntity
import com.bastion.app.data.db.ChallengeProgressEntity
import com.bastion.app.data.db.CheckInEntity
import com.bastion.app.data.db.CovenantDao
import com.bastion.app.data.db.CovenantEntity
import com.bastion.app.data.db.DayLogEntity
import com.bastion.app.data.db.FeedRuleEntity
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.HabitCompletionEntity
import com.bastion.app.data.db.HabitEntity
import com.bastion.app.data.db.LessonReadEntity
import com.bastion.app.data.db.PartnerEntity
import com.bastion.app.data.db.SocialDao
import com.bastion.app.data.db.UrgeLogEntity
import com.bastion.app.data.db.VisionItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Encrypted export and import.
 *
 * Bastion has no server, on purpose — but the cost of that choice is that a lost
 * or reset phone takes the covenant, the streak and the whole journey with it,
 * which are precisely the things the app tells a man are worth keeping. This is
 * the answer that does not require trusting anyone: a file he holds, encrypted
 * with a passphrase only he knows, which he can put wherever he likes.
 *
 * What is NOT in it, and why:
 *   - the signature image and the "why" video. They are large binaries, and the
 *     signature is sealed to this device's Keystore, so it could not be restored
 *     elsewhere even if it were included. The oath text and its date do travel.
 *   - built-in feed rules and the bundled blocklist, which the app regenerates.
 *   - settings, which are a minute's work to re-choose and would otherwise carry
 *     stale device-specific state into a new phone.
 */
class BackupRepository(
    private val backupDao: BackupDao,
    private val covenantDao: CovenantDao,
    private val socialDao: SocialDao,
    private val settings: com.bastion.app.data.prefs.SettingsStore,
) {

    @Serializable
    data class Backup(
        val version: Int = 1,
        val exportedAt: Long,
        val days: List<DayLogEntity> = emptyList(),
        val urges: List<UrgeLogEntity> = emptyList(),
        val habits: List<HabitEntity> = emptyList(),
        val completions: List<HabitCompletionEntity> = emptyList(),
        val challenges: List<ChallengeProgressEntity> = emptyList(),
        val badges: List<BadgeEntity> = emptyList(),
        val checkIns: List<CheckInEntity> = emptyList(),
        val visionItems: List<VisionItemEntity> = emptyList(),
        val lessonsRead: List<LessonReadEntity> = emptyList(),
        val guardedApps: List<GuardedAppEntity> = emptyList(),
        val learnedRules: List<FeedRuleEntity> = emptyList(),
        val userDomains: List<BlockedDomainEntity> = emptyList(),
        val covenant: CovenantEntity? = null,
        val partner: PartnerEntity? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(passphrase: String): ByteArray = withContext(Dispatchers.IO) {
        val backup = Backup(
            exportedAt = System.currentTimeMillis(),
            days = backupDao.days(),
            urges = backupDao.urges(),
            habits = backupDao.habits(),
            completions = backupDao.completions(),
            challenges = backupDao.challenges(),
            badges = backupDao.badges(),
            checkIns = backupDao.checkIns(),
            visionItems = backupDao.visionItems(),
            lessonsRead = backupDao.lessonsRead(),
            guardedApps = backupDao.guardedApps(),
            learnedRules = backupDao.learnedRules(),
            userDomains = backupDao.userDomains(),
            // The signature path is dropped: it points at a file sealed to this
            // device's Keystore and would be a dangling reference anywhere else.
            covenant = covenantDao.covenantOnce()?.copy(signaturePath = null, whyMediaPath = null),
            partner = socialDao.partnerOnce(),
        )
        BackupCodec.encrypt(json.encodeToString(backup).toByteArray(), passphrase)
    }

    /**
     * @throws BackupCodec.WrongPassphrase on a bad passphrase or a file that is
     * not a Bastion backup.
     */
    suspend fun import(payload: ByteArray, passphrase: String): Int = withContext(Dispatchers.IO) {
        val decoded = BackupCodec.decrypt(payload, passphrase)
        val backup = json.decodeFromString<Backup>(decoded.decodeToString())

        // While locked in, a restore brings back the history and nothing that
        // guards him.
        //
        // This was the quietest way out of the whole app, and it needed no
        // permission, no wait and no code. Every row goes back by @Upsert on its
        // own key, so an older file does not delete a guard — it *overwrites*
        // one. Export a backup today, guard Instagram tomorrow, restore the file
        // on Friday, and Instagram's row reverts to whatever it was: a weaker
        // mode, or `enabled = false`. The cooling-off timer never sees it.
        //
        // Worse, `partner` carries `lockPasscodeHash`. A backup taken before the
        // partner set his code restores a row where that hash is null — which
        // silently lifts the partner lock, the one protection built specifically
        // so that the man setting it up cannot be the man who undoes it.
        //
        // So the rule is simply what a backup is honestly for: it exists so a
        // lost phone does not cost him the covenant and the counted days. It was
        // never meant to be a way to roll the guards back, and unlocked it still
        // restores everything.
        val locked = settings.current().tamperLockEnabled ||
            com.bastion.app.guard.lockdown.Lockdown.isActive(settings.current())

        backupDao.restore(
            days = backup.days,
            urges = backup.urges,
            habits = backup.habits,
            completions = backup.completions,
            challenges = backup.challenges,
            badges = backup.badges,
            checkIns = backup.checkIns,
            visionItems = backup.visionItems,
            lessonsRead = backup.lessonsRead,
            guardedApps = if (locked) emptyList() else backup.guardedApps,
            learnedRules = if (locked) emptyList() else backup.learnedRules,
            userDomains = if (locked) emptyList() else backup.userDomains,
            covenant = backup.covenant,
            // Name and number are harmless; the hash is the lock. Restoring the
            // row while keeping whatever hash is currently set means a man can
            // still recover his partner's details without recovering his way
            // past them.
            partner = backup.partner?.let { restored ->
                if (!locked) restored
                else restored.copy(lockPasscodeHash = socialDao.partnerOnce()?.lockPasscodeHash)
            },
        )
        backup.days.size
    }

    /** True when a restore would leave the guards alone; drives the warning in Settings. */
    suspend fun restoreIsLimited(): Boolean = settings.current().let {
        it.tamperLockEnabled || com.bastion.app.guard.lockdown.Lockdown.isActive(it)
    }

    fun suggestedFileName(): String {
        val date = java.time.LocalDate.now()
        return "bastion-backup-$date.bastion"
    }
}
