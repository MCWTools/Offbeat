package com.auralis.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.auralis.player.Graph
import com.auralis.player.playback.LevelProcessor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Bars that stand on the border of the (rounded-square) cover and jump with the music. Bass at the bottom, treble at the top. */
@Composable
fun CoverVisualizer(modifier: Modifier, coverFraction: Float) {
    val pm = Graph.player
    val accent = LocalAccent.current
    val bands = remember { FloatArray(LevelProcessor.BANDS) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { t ->
            val sp = pm.spectrum(); val on = pm.isPlaying
            for (i in bands.indices) bands[i] += ((if (on) sp[i] else 0f) - bands[i]) * 0.45f
            tick = t
        }
    }
    Canvas(modifier) {
        tick.let { }
        val c = Offset(size.width / 2, size.height / 2)
        val half = size.minDimension / 2 * coverFraction
        val maxLen = size.minDimension / 2 - half - 3f * density
        val n = 180; val e = 5f
        val gap = 4f * density
        // smooth, mirrored (left = right) wave heights: bass at the bottom, treble at the top
        val h = FloatArray(n) { k ->
            val u = abs((k.toFloat() / n - 0.5f) * 2f)
            val f = u * (LevelProcessor.BANDS - 1); val a = f.toInt().coerceIn(0, LevelProcessor.BANDS - 1); val b = (a + 1).coerceAtMost(LevelProcessor.BANDS - 1)
            val v = bands[a] + (bands[b] - bands[a]) * (f - a)
            sqrt(v.coerceIn(0f, 1f))
        }
        val sm = FloatArray(n) { k -> (h[(k + n - 2) % n] + 2 * h[(k + n - 1) % n] + 3 * h[k] + 2 * h[(k + 1) % n] + h[(k + 2) % n]) / 9f }
        val pts = Array(n) { k ->
            val th = 2f * PI.toFloat() * k / n + PI.toFloat() / 2f
            val cs = cos(th); val sn = sin(th)
            val px = half * Math.signum(cs) * abs(cs).pow(2f / e)
            val py = half * Math.signum(sn) * abs(sn).pow(2f / e)
            var gx = Math.signum(px) * abs(px / half).pow(e - 1f); var gy = Math.signum(py) * abs(py / half).pow(e - 1f)
            val gl = sqrt(gx * gx + gy * gy).coerceAtLeast(1e-4f); gx /= gl; gy /= gl
            val r = gap + 2f * density + sm[k] * (maxLen - gap)
            Offset(c.x + px + gx * r, c.y + py + gy * r)
        }
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo((pts[n - 1].x + pts[0].x) / 2, (pts[n - 1].y + pts[0].y) / 2)
            for (k in 0 until n) { val p = pts[k]; val q = pts[(k + 1) % n]; quadraticBezierTo(p.x, p.y, (p.x + q.x) / 2, (p.y + q.y) / 2) }
            close()
        }
        drawPath(path, accent.copy(alpha = 0.16f))
        drawPath(path, accent.copy(alpha = 0.20f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 9f * density, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4f * density, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
