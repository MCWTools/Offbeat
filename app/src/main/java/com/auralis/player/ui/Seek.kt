package com.auralis.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box

/**
 * Seek bar with a glowing "played" part and a round dot. While dragging, a bubble above the dot shows the target time
 * and [onDragTime] reports it so the left label can show it too.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    accent: Color,
    onSeek: (Long) -> Unit,
    onDragTime: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = durationMs.coerceAtLeast(1)
    val frac = drag ?: (positionMs.toFloat() / dur).coerceIn(0f, 1f)
    val thumbR by animateDpAsState(if (drag != null) 11.dp else 7.dp, tween(150), label = "thumb")
    val glow by animateFloatAsState(if (drag != null) 1f else 0.7f, tween(200), label = "glow")

    BoxWithConstraints(modifier.fillMaxWidth().height(52.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            Modifier.fillMaxWidth().height(52.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f); onDragTime((drag!! * dur).toLong()) },
                        onDragEnd = { val f = drag; drag = null; onDragTime(null); if (f != null) onSeek((f * dur).toLong()) },
                        onDragCancel = { drag = null; onDragTime(null) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            drag = (change.position.x / size.width).coerceIn(0f, 1f); onDragTime((drag!! * dur).toLong())
                        },
                    )
                },
        ) {
            val cy = size.height / 2 + 6.dp.toPx()
            val pad = thumbR.toPx()
            val x0 = pad; val x1 = size.width - pad
            val x = x0 + (x1 - x0) * frac
            val h = 4.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.18f), Offset(x0, cy), Offset(x1, cy), h, StrokeCap.Round)
            if (x > x0) {
                // glow layers under the played part
                drawLine(accent.copy(alpha = 0.10f * glow), Offset(x0, cy), Offset(x, cy), h * 4.2f, StrokeCap.Round)
                drawLine(accent.copy(alpha = 0.18f * glow), Offset(x0, cy), Offset(x, cy), h * 2.6f, StrokeCap.Round)
                drawLine(accent, Offset(x0, cy), Offset(x, cy), h, StrokeCap.Round)
            }
            drawCircle(accent.copy(alpha = 0.12f * glow), pad * 2.6f, Offset(x, cy))
            drawCircle(accent.copy(alpha = 0.25f * glow), pad * 1.7f, Offset(x, cy))
            drawCircle(Color.White, pad, Offset(x, cy))
        }
        if (drag != null) {
            val bubbleW = 56.dp
            val px = (widthPx * frac)
            val xDp = with(androidx.compose.ui.platform.LocalDensity.current) { px.toDp() } - bubbleW / 2
            Text(
                fmt((frac * dur).toLong()),
                color = Color.Black, fontSize = 12.sp,
                modifier = Modifier.offset(x = xDp.coerceIn(0.dp, maxWidth - bubbleW), y = 0.dp)
                    .clip(RoundedCornerShape(10.dp)).background(accent)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .let { it },
            )
        }
    }
}
