package com.bastion.app.feature.panic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionTheme
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.ScriptureStyle
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.CovenantEntity
import com.bastion.app.guard.accessibility.BastionAccessibilityService
import kotlinx.coroutines.delay

/**
 * Hold the Line.
 *
 * The single most important screen in Bastion. An urge is a wave, not a
 * command, and interrupting the loop for ninety seconds changes the odds
 * enormously. So this screen does exactly four things: slows the breath, puts
 * the man's own face and reasons in front of him, hands him something physical
 * to do, and makes another human one tap away.
 *
 * Nothing here punishes. The redirect is a substitution, never penance, and the
 * exit that says "it went the other way" is as calm as the one that says it
 * didn't.
 */
class PanicActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BastionTheme { PanicFlow(onClose = { finish() }) } }
    }
}

private enum class PanicStep { BREATHE, WHY, ANCHOR, MOVE, OUTCOME }

@Composable
private fun PanicFlow(onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.bastion.app.data.prefs.Settings()
    )
    var step by remember { mutableStateOf(PanicStep.BREATHE) }
    var covenant by remember { mutableStateOf<CovenantEntity?>(null) }
    var anchor by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) {
        covenant = graph.growth.covenantOnce()
        val brief = graph.content.briefForDay(graph.journey.dayOfJourney())
        val side = brief?.side(settings.faithMode)
        if (side != null) anchor = side.anchor to side.anchorRef
    }

    DawnBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(260)) },
                label = "panic-step",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    PanicStep.BREATHE -> BreatheStep(onDone = { step = PanicStep.WHY })
                    PanicStep.WHY -> WhyStep(covenant = covenant, onNext = { step = PanicStep.ANCHOR })
                    PanicStep.ANCHOR -> AnchorStep(anchor = anchor, onNext = { step = PanicStep.MOVE })
                    PanicStep.MOVE -> MoveStep(onNext = { step = PanicStep.OUTCOME })
                    PanicStep.OUTCOME -> OutcomeStep(
                        faithMode = settings.faithMode,
                        onHeld = {
                            graph.settings.recordPanic()
                            graph.journey.logUrge(
                                resisted = true,
                                intensity = 4,
                                mood = null,
                                trigger = null,
                                contextApp = BastionAccessibilityService.foregroundApp.value,
                                place = null,
                                note = "Held the line",
                            )
                            onClose()
                        },
                        onSlip = { onClose() },
                        onMentor = { onClose() },
                        onPartner = { contact ->
                            context.startActivity(
                                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$contact")).putExtra(
                                    "sms_body",
                                    "I'm having a hard moment and I'm telling you instead of hiding it.",
                                )
                            )
                        },
                        graph = graph,
                    )
                }
            }

            if (step != PanicStep.OUTCOME) {
                Spacer(Modifier.height(12.dp))
                QuietButton(
                    text = "I'm okay — close this",
                    onClick = { step = PanicStep.OUTCOME },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Box breathing: four counts in, hold, out, hold. Roughly thirty seconds. */
@Composable
private fun BreatheStep(onDone: () -> Unit) {
    val phases = listOf("Breathe in" to 4_000, "Hold" to 4_000, "Breathe out" to 4_000, "Hold" to 4_000)
    var index by remember { mutableIntStateOf(0) }
    var cycles by remember { mutableIntStateOf(0) }

    val (label, duration) = phases[index % phases.size]
    val expanded = index % phases.size == 0 || index % phases.size == 1
    val scale by animateFloatAsState(
        targetValue = if (expanded) 1f else 0.55f,
        animationSpec = tween(duration, easing = androidx.compose.animation.core.LinearEasing),
        label = "breath",
    )

    LaunchedEffect(index) {
        delay(duration.toLong())
        if (index % phases.size == phases.size - 1) cycles++
        if (cycles >= 2) onDone() else index++
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "You're stronger than this moment.",
            style = MaterialTheme.typography.headlineMedium,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "It passes either way. Breathe with the circle.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))

        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = (size.minDimension / 2f) * scale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            BastionColors.Bronze.copy(alpha = 0.28f),
                            BastionColors.Bronze.copy(alpha = 0.02f),
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                )
                drawCircle(
                    color = BastionColors.Bronze,
                    radius = radius,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Text(label, style = MaterialTheme.typography.titleLarge, color = BastionColors.BronzeBright)
        }

        Spacer(Modifier.height(40.dp))
        SectionLabel("Round ${cycles + 1} of 2")
    }
}

@Composable
private fun WhyStep(covenant: CovenantEntity?, onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel("Why you started")
        Spacer(Modifier.height(20.dp))

        val media = covenant?.whyMediaPath
        if (media != null) {
            WhyVideo(path = media)
            Spacer(Modifier.height(24.dp))
        }

        Text(
            covenant?.whyText?.takeIf { it.isNotBlank() }
                ?: "Add your Why when this passes. It works.",
            style = ScriptureStyle,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        PrimaryButton("Keep going", onNext, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AnchorStep(anchor: Pair<String, String>?, onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("◇", style = MaterialTheme.typography.displaySmall, color = BastionColors.Bronze)
        Spacer(Modifier.height(28.dp))
        Text(
            anchor?.first ?: "The urge is a wave. You are the shore.",
            style = ScriptureStyle,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel(anchor?.second ?: "")
        Spacer(Modifier.height(36.dp))
        PrimaryButton("Keep going", onNext, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MoveStep(onNext: () -> Unit) {
    // Redirects, not penance. Every one of these is something that genuinely
    // shifts state — never anything that hurts.
    val options = remember {
        listOf(
            "Stand up and do 20 press-ups",
            "Cold water on your face and wrists",
            "Step outside for two minutes",
            "Put your shoes on and walk to the end of the road",
            "Make a drink and stand in a different room",
        )
    }
    val choice = remember { options.random() }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel("Change the state you're in")
        Spacer(Modifier.height(20.dp))
        Text(
            choice,
            style = MaterialTheme.typography.headlineLarge,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Go. This screen will wait.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        PrimaryButton("Done", onNext, Modifier.fillMaxWidth())
    }
}

@Composable
private fun OutcomeStep(
    faithMode: Boolean,
    onHeld: suspend () -> Unit,
    onSlip: () -> Unit,
    onMentor: () -> Unit,
    onPartner: (String) -> Unit,
    graph: BastionGraph,
) {
    val partner by graph.social.partner.collectAsStateWithLifecycle(initialValue = null)
    var finishing by remember { mutableStateOf(false) }

    LaunchedEffect(finishing) { if (finishing) onHeld() }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "How are you now?",
            style = MaterialTheme.typography.headlineMedium,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (faithMode) "Either way, you're not condemned."
            else "Either way, it's information, not a verdict.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(34.dp))

        PrimaryButton("I held the line", { finishing = true }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))

        partner?.let { p ->
            QuietButton(
                text = "Message ${p.name}",
                onClick = { onPartner(p.contact) },
                modifier = Modifier.fillMaxWidth(),
                accent = BastionColors.SageBright,
            )
            Spacer(Modifier.height(10.dp))
        }

        QuietButton("Talk it through", onMentor, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        QuietButton(
            text = "It went the other way",
            onClick = onSlip,
            modifier = Modifier.fillMaxWidth(),
            accent = BastionColors.Amber,
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun WhyVideo(path: String) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                useController = false
                player = androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.parse(path)))
                    prepare()
                    playWhenReady = true
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                }
            }
        },
        onRelease = { view -> view.player?.release() },
    )
}
