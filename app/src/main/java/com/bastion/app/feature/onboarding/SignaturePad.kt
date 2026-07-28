package com.bastion.app.feature.onboarding

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.security.CovenantVault

/** One continuous pen stroke. */
typealias Stroke2D = MutableList<Offset>

/**
 * Where a man signs his name.
 *
 * Deliberately a finger signature rather than a checkbox: the physical act of
 * writing your own name is what makes a commitment device work, and a tick box
 * is something you click without noticing.
 */
@Composable
fun SignaturePad(
    strokes: MutableList<Stroke2D>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BastionColors.MidnightDeep)
            .border(1.dp, BastionColors.Outline, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (strokes.isEmpty()) {
            Text(
                "Sign here",
                color = BastionColors.TextMuted,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            strokes.add(mutableStateListOf(offset))
                        },
                        onDrag = { change, _ ->
                            strokes.lastOrNull()?.add(change.position)
                            change.consume()
                        },
                    )
                }
        ) {
            strokes.forEach { stroke ->
                for (i in 1 until stroke.size) {
                    drawLine(
                        color = BastionColors.BronzeBright,
                        start = stroke[i - 1],
                        end = stroke[i],
                        strokeWidth = 4.5f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/**
 * True once there is enough ink to be a signature rather than a stray tap.
 *
 * A single tap produced one stroke of one point: `isNotEmpty()` was satisfied,
 * the draw loop (`1 until size`) rendered nothing, and a man could sign his
 * covenant with a blank image.
 */
fun hasRealSignature(strokes: List<List<Offset>>): Boolean {
    val points = strokes.sumOf { it.size }
    val drawnLength = strokes.sumOf { stroke ->
        (1 until stroke.size).sumOf { i ->
            val dx = stroke[i].x - stroke[i - 1].x
            val dy = stroke[i].y - stroke[i - 1].y
            kotlin.math.sqrt(dx * dx + dy * dy).toDouble()
        }
    }
    return points >= MIN_SIGNATURE_POINTS && drawnLength >= MIN_SIGNATURE_LENGTH_PX
}

private const val MIN_SIGNATURE_POINTS = 8
private const val MIN_SIGNATURE_LENGTH_PX = 120.0

/**
 * Rasterises the signature to a PNG in app-private storage. Never leaves the
 * device; it exists so a man can look at his own signature on a hard night.
 *
 * Suspends on the IO dispatcher: encoding a PNG on the main thread janked the
 * final transition of onboarding, which is the one moment meant to feel weighty.
 */
suspend fun saveSignature(
    context: Context,
    strokes: List<List<Offset>>,
    sourceWidth: Float,
    sourceHeight: Float,
    width: Int = 1000,
): String? = withContext(Dispatchers.IO) {
    if (!hasRealSignature(strokes) || sourceWidth <= 0f || sourceHeight <= 0f) {
        return@withContext null
    }

    // Height derived from the pad's own aspect ratio. A fixed 1000x400 against a
    // roughly 2:1 pad stretched every signature horizontally, so what was saved
    // was not what the man drew.
    val height = (width * (sourceHeight / sourceWidth)).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = 0xFFE8C877.toInt()
        strokeWidth = 9f
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isAntiAlias = true
    }

    // One uniform scale, so the drawing keeps its proportions.
    val scale = width / sourceWidth

    strokes.forEach { stroke ->
        val path = android.graphics.Path()
        stroke.forEachIndexed { index, point ->
            val x = point.x * scale
            val y = point.y * scale
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    // Encrypted at rest rather than written straight to filesDir: private-by-
    // permission does not protect a handwritten signature from anything holding
    // the filesystem itself.
    val png = java.io.ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }
    CovenantVault(context).write("covenant_signature.png", png)
}
