package com.bastion.app.data.repo

import com.bastion.app.data.content.Challenge
import com.bastion.app.data.content.ContentRepository
import com.bastion.app.data.content.HabitDef
import com.bastion.app.data.db.BadgeEntity
import com.bastion.app.data.db.ChallengeProgressEntity
import com.bastion.app.data.db.CovenantDao
import com.bastion.app.data.db.CovenantEntity
import com.bastion.app.data.db.HabitCompletionEntity
import com.bastion.app.data.db.HabitDao
import com.bastion.app.data.db.HabitEntity
import com.bastion.app.data.db.ProgressDao
import com.bastion.app.data.db.VisionItemEntity
import com.bastion.app.data.db.VisionType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

/** The BECOME pillar: habits, challenges, the covenant and the vision reel. */
class GrowthRepository(
    private val habitDao: HabitDao,
    private val progressDao: ProgressDao,
    private val covenantDao: CovenantDao,
    private val content: ContentRepository,
) {

    val activeHabits: Flow<List<HabitEntity>> = habitDao.activeHabits()
    val allHabits: Flow<List<HabitEntity>> = habitDao.allHabits()
    val activeChallenges: Flow<List<ChallengeProgressEntity>> = progressDao.activeChallenges()
    val allChallenges: Flow<List<ChallengeProgressEntity>> = progressDao.allChallenges()
    val covenant: Flow<CovenantEntity?> = covenantDao.covenant()
    val visionItems: Flow<List<VisionItemEntity>> = covenantDao.visionItems()
    val badges: Flow<List<BadgeEntity>> = progressDao.badges()

    fun completionsToday(): Flow<List<HabitCompletionEntity>> =
        habitDao.completionsOn(LocalDate.now().toEpochDay())

    fun completionsSince(epochDay: Long): Flow<List<HabitCompletionEntity>> =
        habitDao.completionsSince(epochDay)

    suspend fun catalogue(): List<HabitDef> = content.habitCatalogue().habits

    suspend fun challengeCatalogue(): List<Challenge> = content.challenges().challenges

    suspend fun adoptHabit(def: HabitDef, sortOrder: Int) {
        habitDao.upsert(
            HabitEntity(
                id = def.id,
                name = def.name,
                domain = def.domain,
                emoji = def.icon,
                target = def.defaultTarget,
                why = def.why,
                sortOrder = sortOrder,
            )
        )
    }

    suspend fun setHabitActive(habit: HabitEntity, active: Boolean) {
        habitDao.upsert(habit.copy(active = active, updatedAt = System.currentTimeMillis()))
    }

    suspend fun toggleHabit(habitId: String, done: Boolean, epochDay: Long = LocalDate.now().toEpochDay()) {
        if (done) habitDao.complete(HabitCompletionEntity(habitId, epochDay))
        else habitDao.uncomplete(habitId, epochDay)
    }

    suspend fun startChallenge(challengeId: String) {
        progressDao.upsertChallenge(
            ChallengeProgressEntity(
                challengeId = challengeId,
                startedEpochDay = LocalDate.now().toEpochDay(),
                active = true,
            )
        )
    }

    suspend fun abandonChallenge(challengeId: String) {
        progressDao.challenge(challengeId)?.let {
            progressDao.upsertChallenge(it.copy(active = false, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Marks a day of a challenge done, awarding the badge when the last day lands. */
    suspend fun completeChallengeDay(challengeId: String, day: Int) {
        val progress = progressDao.challenge(challengeId) ?: return
        val days = progress.completedDaysCsv.split(',').filter { it.isNotBlank() }.toMutableSet()
        days += day.toString()
        val definition = challengeCatalogue().firstOrNull { it.id == challengeId }
        val finished = definition != null && days.size >= definition.days

        val updated = progress.copy(
            completedDaysCsv = days.joinToString(","),
            // `&& progress.active`: logging a day on a challenge the user
            // abandoned used to silently revive it.
            active = progress.active && !finished,
            completedAt = if (finished) System.currentTimeMillis() else null,
            updatedAt = System.currentTimeMillis(),
        )
        val badge = if (finished && definition != null && definition.badgeId.isNotBlank()) {
            BadgeEntity(
                definition.badgeId,
                definition.badgeName.ifBlank { definition.name },
                System.currentTimeMillis(),
            )
        } else null

        progressDao.completeChallenge(updated, badge)
    }

    suspend fun awardBadge(id: String, name: String) {
        progressDao.awardBadge(BadgeEntity(id, name, System.currentTimeMillis()))
    }

    suspend fun saveCovenant(
        oathText: String,
        signaturePath: String?,
        whyText: String?,
        whyMediaPath: String?,
        whyMediaType: String?,
    ) {
        covenantDao.upsert(
            CovenantEntity(
                oathText = oathText,
                signaturePath = signaturePath,
                signedAt = System.currentTimeMillis(),
                whyText = whyText,
                whyMediaPath = whyMediaPath,
                whyMediaType = whyMediaType,
            )
        )
    }

    suspend fun covenantOnce(): CovenantEntity? = covenantDao.covenantOnce()

    suspend fun addVisionItem(type: VisionType, text: String?, mediaPath: String?) {
        covenantDao.upsertVision(
            VisionItemEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                text = text,
                mediaPath = mediaPath,
            )
        )
    }

    suspend fun removeVisionItem(id: String) = covenantDao.deleteVision(id)

    /**
     * The "Man You're Becoming" profile: a 0..1 score per life domain, driven by
     * habits actually completed over the last four weeks rather than by intent.
     */
    fun domainScores(completions: List<HabitCompletionEntity>, habits: List<HabitEntity>): Map<String, Float> {
        val byId = habits.associateBy { it.id }
        val windowDays = 28f
        return habits.map { it.domain }.distinct().associateWith { domain ->
            val domainHabits = habits.filter { it.domain == domain }
            if (domainHabits.isEmpty()) return@associateWith 0f
            val hits = completions.count { byId[it.habitId]?.domain == domain }
            (hits / (windowDays * domainHabits.size)).coerceIn(0f, 1f)
        }
    }
}
