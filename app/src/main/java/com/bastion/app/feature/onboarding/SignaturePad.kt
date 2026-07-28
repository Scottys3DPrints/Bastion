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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionColors
import java.io.File
import java.io.FileOutputStream

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
 * Rasterises the signature to a PNG in app-private storage. Never leaves the
 * device; it exists so a man can look at his own signature on a hard night.
 */
fun saveSignature(
    context: Context,
    strokes: List<List<Offset>>,
    width: Int = 1000,
    height: Int = 400,
    sourceWidth: Float,
    sourceHeight: Float,
): String? {
    if (strokes.isEmpty() || sourceWidth <= 0f || sourceHeight <= 0f) return null

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

    val scaleX = width / sourceWidth
    val scaleY = height / sourceHeight

    strokes.forEach { stroke ->
        val path = android.graphics.Path()
        stroke.forEachIndexed { index, point ->
            val x = point.x * scaleX
            val y = point.y * scaleY
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    val file = File(context.filesDir, "covenant_signature.png")
    return runCatching {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrNull()
}

@Suppress("unused")
private val strokeStyleReference = Stroke(width = 4f, join = StrokeJoin.Round)
