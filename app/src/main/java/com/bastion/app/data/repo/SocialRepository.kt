package com.bastion.app.data.repo

import com.bastion.app.core.security.PasscodeLock
import com.bastion.app.data.content.ContentRepository
import com.bastion.app.data.content.MentorFollowUp
import com.bastion.app.data.content.MentorIntent
import com.bastion.app.data.db.MentorMessageEntity
import com.bastion.app.data.db.PartnerEntity
import com.bastion.app.data.db.SocialDao
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.math.absoluteValue

/** Brotherhood and the Mentor. */
class SocialRepository(
    private val socialDao: SocialDao,
    private val content: ContentRepository,
) {

    val partner: Flow<PartnerEntity?> = socialDao.partner()
    val mentorHistory: Flow<List<MentorMessageEntity>> = socialDao.mentorHistory()

    suspend fun partnerOnce(): PartnerEntity? = socialDao.partnerOnce()

    suspend fun savePartner(
        name: String,
        contact: String,
        shareCheckIns: Boolean,
        shareSlips: Boolean,
        shareGuardChanges: Boolean,
    ) {
        val existing = socialDao.partnerOnce()
        socialDao.upsertPartner(
            PartnerEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name,
                contact = contact,
                shareCheckIns = shareCheckIns,
                shareSlips = shareSlips,
                shareGuardChanges = shareGuardChanges,
                lockPasscodeHash = existing?.lockPasscodeHash,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removePartner(id: String) = socialDao.removePartner(id)

    /** Pass the raw code; it is salted and slow-hashed before it is stored. */
    suspend fun setPartnerPasscode(passcode: String?) {
        val stored = passcode?.takeIf { it.isNotBlank() }?.let { PasscodeLock.hash(it) }
        socialDao.partnerOnce()?.let { socialDao.upsertPartner(it.copy(lockPasscodeHash = stored)) }
    }

    /** Whether a partner code is set, and therefore whether the lock can bite. */
    suspend fun hasPasscode(): Boolean = socialDao.partnerOnce()?.lockPasscodeHash != null

    suspend fun verifyPasscode(passcode: String): Boolean =
        PasscodeLock.verify(passcode, socialDao.partnerOnce()?.lockPasscodeHash)

    // --- Mentor ----------------------------------------------------------

    suspend fun say(text: String, fromUser: Boolean, intentId: String? = null) {
        socialDao.addMessage(
            MentorMessageEntity(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                fromUser = fromUser,
                text = text,
                intentId = intentId,
            )
        )
    }

    suspend fun clearMentorHistory() = socialDao.clearMentorHistory()

    suspend fun opener(faithMode: Boolean): String {
        val openers = content.mentorScript().openers
        val list = if (faithMode) openers.faith else openers.discipline
        return list.randomOrNull() ?: "I'm here. What's going on?"
    }

    /**
     * Picks the best-matching scripted reply.
     *
     * There is no language model behind this by design — the Mentor works with
     * no network, no account and nothing about the user leaving the device, at
     * 1am on aeroplane mode. It trades cleverness for always being there.
     */
    suspend fun reply(userText: String, faithMode: Boolean): MentorReply {
        val script = content.mentorScript()
        val normalised = userText.lowercase()

        val scored = script.intents.mapNotNull { intent ->
            val hits = intent.matchKeywords.count { keyword ->
                keyword.isNotBlank() && normalised.contains(keyword.lowercase())
            }
            if (hits == 0) null else ScoredIntent(intent, hits)
        }

        // Safety outranks relevance: a crisis intent wins on any hit at all.
        val chosen = scored
            .sortedWith(compareBy({ it.intent.priority }, { -it.hits }))
            .firstOrNull()?.intent
            ?: script.intents.firstOrNull { it.id == FALLBACK_INTENT }

        val options = chosen?.responses?.forMode(faithMode).orEmpty()
        val text = options.pickStable(userText) ?: DEFAULT_REPLY

        return MentorReply(
            text = text,
            intentId = chosen?.id,
            followUps = chosen?.followUps.orEmpty(),
            isCrisis = chosen?.id == CRISIS_INTENT,
        )
    }

    /**
     * Deterministic per input, so re-reading the thread never rewrites history.
     *
     * floorMod rather than absoluteValue: `Int.MIN_VALUE.absoluteValue` is still
     * negative, which made a rare but real IndexOutOfBounds on the Mentor path —
     * the last place in the app that should ever crash.
     */
    private fun List<String>.pickStable(seed: String): String? =
        if (isEmpty()) null else this[Math.floorMod(seed.hashCode(), size)]

    private data class ScoredIntent(val intent: MentorIntent, val hits: Int)

    data class MentorReply(
        val text: String,
        val intentId: String?,
        val followUps: List<MentorFollowUp>,
        val isCrisis: Boolean,
    )

    suspend fun quickPrompts(faithMode: Boolean): List<MentorIntent> =
        content.mentorScript().intents
            .filter { it.id != CRISIS_INTENT && it.label.isNotBlank() }
            .filter { it.responses.forMode(faithMode).isNotEmpty() }
            .sortedBy { it.priority }
            .take(6)

    private companion object {
        const val CRISIS_INTENT = "crisis"
        const val FALLBACK_INTENT = "fallback"
        const val DEFAULT_REPLY = "I'm here. What's loudest right now?"
    }
}
