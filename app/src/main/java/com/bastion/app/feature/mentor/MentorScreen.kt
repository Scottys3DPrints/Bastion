package com.bastion.app.feature.mentor

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.LinkButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.bastion.app.core.design.BastionTopBar
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.content.MentorIntent
import com.bastion.app.data.db.MentorMessageEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The Mentor.
 *
 * Available at 1am, on aeroplane mode, with no account and no network. It is a
 * curated script rather than a language model, and that is a deliberate trade:
 * it gives up cleverness to guarantee it is always there and that nothing a man
 * types ever leaves his phone.
 *
 * It is not therapy and says so. Anything that reads like crisis is routed
 * straight to real human help rather than answered.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MentorScreen(faithMode: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    val history by graph.social.mentorHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by remember { mutableStateOf("") }
    var prompts by remember { mutableStateOf<List<MentorIntent>>(emptyList()) }
    var typing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(faithMode) {
        prompts = graph.social.quickPrompts(faithMode)
    }

    // Greet once, ever — decided by a persisted flag rather than by whether the
    // message list happens to be empty. It is empty for the first frames of
    // every launch, so the old check posted a new opener over real history each
    // time the screen opened, and again on every mode toggle.
    LaunchedEffect(Unit) {
        if (!graph.settings.current().mentorOpenerSent) {
            graph.social.say(graph.social.opener(faithMode), fromUser = false)
            graph.settings.setMentorOpenerSent(true)
        }
    }

    LaunchedEffect(history.size, typing) {
        val last = history.size - if (typing) 0 else 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    fun send(text: String) {
        if (text.isBlank()) return
        scope.launch {
            graph.social.say(text, fromUser = true)
            val reply = graph.social.reply(text, faithMode)
            // A pause so the reply reads as answered rather than auto-completed.
            // Never on the crisis path: safety text waits for nothing.
            if (!reply.isCrisis) {
                typing = true
                delay(Random.nextLong(600, 900))
                typing = false
            }
            graph.social.say(reply.text, fromUser = false, intentId = reply.intentId)
        }
        draft = ""
    }

    DawnBackground(intensity = 0.3f) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
        ) {
            // The shared header, not a hand-rolled copy of it. The body below is
            // a chat rather than a scrolling page — which is exactly why this
            // screen takes the bar on its own instead of BastionScaffold, whose
            // scrolling column cannot hold a list that fills the leftover space.
            BastionTopBar(
                title = "Mentor",
                subtitle = "Offline · nothing leaves this phone",
                subtitleColor = BastionColors.SageBright,
                onBack = onBack,
                action = {
                    if (history.isNotEmpty()) {
                        LinkButton("Start fresh", BastionColors.TextMuted) { confirmClear = true }
                    }
                },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Space.gutter),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                items(history, key = { it.id }) { message -> MessageBubble(message) }
                if (typing) item { TypingBubble() }
            }

            // The chips come back whenever the thread is resting on the Mentor's
            // reply and nothing is half-typed — a man stuck for words at 1am is
            // exactly who needed them, and they only ever showed at the start.
            if (prompts.isNotEmpty() && !typing && draft.isBlank() && history.lastOrNull()?.fromUser != true) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Space.gutter, vertical = Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    prompts.forEach { intent ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(BastionColors.Surface)
                                .border(1.dp, BastionColors.Outline, RoundedCornerShape(18.dp))
                                .clickable(role = Role.Button) { send(intent.label) }
                                .padding(horizontal = Space.lg, vertical = Space.md)
                        ) {
                            Text(
                                intent.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = BastionColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("What's going on?") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Space.lg),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BastionColors.Bronze,
                        unfocusedBorderColor = BastionColors.Outline,
                        focusedTextColor = BastionColors.TextPrimary,
                        unfocusedTextColor = BastionColors.TextPrimary,
                        cursorColor = BastionColors.Bronze,
                    ),
                )
                Spacer(Modifier.padding(Space.xs))
                PrimaryButton("Send", { send(draft) }, enabled = draft.isNotBlank())
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = BastionColors.Surface,
            titleContentColor = BastionColors.TextPrimary,
            textContentColor = BastionColors.TextSecondary,
            title = { Text("Clear this conversation?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "The thread goes. Nothing else changes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                LinkButton("Clear it", BastionColors.BronzeBright) {
                    scope.launch {
                        graph.social.clearMentorHistory()
                        // An empty screen reads as broken, and the once-ever
                        // opener flag stays set, so post a fresh one here.
                        graph.social.say(graph.social.opener(faithMode), fromUser = false)
                    }
                    confirmClear = false
                }
            },
            dismissButton = {
                LinkButton("Not now", BastionColors.TextMuted) { confirmClear = false }
            },
        )
    }
}

/** Text-styled actions that are still real buttons: 48dp target, button semantics. */

/** The beat before a scripted reply lands, so it reads as considered. */
@Composable
private fun TypingBubble() {
    val transition = rememberInfiniteTransition(label = "typing")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = Space.xs, bottomEnd = 18.dp))
            .background(BastionColors.Surface)
            .padding(horizontal = Space.lg, vertical = Space.lg)
    ) {
        Text(
            "• • •",
            style = MaterialTheme.typography.bodyLarge,
            color = BastionColors.TextMuted,
            modifier = Modifier.alpha(pulse),
        )
    }
}

/**
 * Real, tappable routes to a human.
 *
 * The crisis path used to be styling only — an amber border and a heading. On
 * the one path where the app is explicitly out of its depth, reaching help must
 * cost one tap, not a copied phone number.
 *
 * Uses ACTION_DIAL rather than ACTION_CALL on purpose: it opens the dialler
 * pre-filled and lets the person press call themselves, and it needs no
 * permission. The region-specific numbers are labelled as such, because guessing
 * someone's country and dialling it for them would be worse than useless.
 */
@Composable
private fun CrisisActions() {
    val context = LocalContext.current

    fun dial(number: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Column {
        CrisisButton("Find a helpline near you") {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://findahelpline.com"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        CrisisButton("Call 988 · US & Canada") { dial("988") }
        Spacer(Modifier.height(Space.sm))
        CrisisButton("Call 116 123 · UK & Ireland") { dial("116123") }
    }
}

@Composable
private fun CrisisButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(Space.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, BastionColors.Amber),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = BastionColors.BronzeBright,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Who said what, readable at a glance.
 *
 * Both sides used to be `fillMaxWidth(0.86f)` on a near-identical surface, so
 * two bubbles differed by fourteen percent of the screen and one shade of navy —
 * which is to say they did not differ. Scrolling back through a conversation you
 * could not tell your own words from the Mentor's without reading them.
 *
 * Three things carry the distinction now, so no single one has to do it alone:
 * the man's words are narrower and pushed right, sit on the lighter raised
 * surface with a bronze edge — his colour everywhere else in the app — and read
 * in full-strength text. The Mentor's are wider, flush left, flat on the base
 * surface, and quieter. A crisis reply overrides all of it in amber, because
 * that one must never be mistaken for conversation.
 */
@Composable
private fun MessageBubble(message: MentorMessageEntity) {
    val fromUser = message.fromUser
    val isCrisis = message.intentId == "crisis"
    val corner = 18.dp

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                // The crisis card holds three full-width buttons, so it is the
                // one bubble allowed the whole column.
                .fillMaxWidth(if (isCrisis) 1f else if (fromUser) 0.78f else 0.88f)
                .clip(
                    RoundedCornerShape(
                        topStart = corner,
                        topEnd = corner,
                        // The squared corner points back at its speaker.
                        bottomStart = if (fromUser) corner else Space.xs,
                        bottomEnd = if (fromUser) Space.xs else corner,
                    )
                )
                .background(
                    when {
                        isCrisis -> BastionColors.AmberSoft
                        fromUser -> BastionColors.SurfaceHigh
                        else -> BastionColors.Surface
                    }
                )
                .then(
                    when {
                        isCrisis -> Modifier.border(
                            1.dp,
                            BastionColors.Amber,
                            RoundedCornerShape(corner),
                        )
                        // Bronze is the man's colour throughout the app; on a
                        // dark surface an edge separates far better than another
                        // shade of fill.
                        fromUser -> Modifier.border(
                            1.dp,
                            BastionColors.BronzeDeep,
                            RoundedCornerShape(
                                topStart = corner,
                                topEnd = corner,
                                bottomStart = corner,
                                bottomEnd = Space.xs,
                            ),
                        )
                        else -> Modifier
                    }
                )
                .padding(Space.lg)
        ) {
            Column {
                if (isCrisis) {
                    SectionLabel("Please read this", color = BastionColors.Amber)
                    Spacer(Modifier.height(Space.sm))
                }
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (fromUser) BastionColors.TextPrimary else BastionColors.TextSecondary,
                )
                if (isCrisis) {
                    Spacer(Modifier.height(Space.lg))
                    CrisisActions()
                }
            }
        }
    }
}
