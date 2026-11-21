package com.auralis.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.player.Graph

/** Flips the screen to landscape and shows only what you need while driving: huge buttons, big title. */
@Composable
fun DrivingMode(onExit: () -> Unit) {
    val pm = Graph.player
    val accent = LocalAccent.current
    val act = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    BackHandler(true) { onExit() }
    var drag by remember { mutableStateOf<Long?>(null) }
    val song = pm.current
    Box(Modifier.fillMaxSize().background(Color(0xFF050507)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}) {
        Row(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (Graph.store.vizOn) CoverVisualizer(Modifier.fillMaxSize(), 0.82f)
                ArtImage(song, Modifier.fillMaxSize(0.82f), 480, 24.dp, big = true)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                Column {
                    Text(song?.title ?: "Nothing playing", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(song?.artist ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column {
                    SeekBar(pm.positionMs, pm.durationMs, accent, onSeek = { pm.seekTo(it) }, onDragTime = { drag = it })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(drag ?: pm.positionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        Text(fmt(pm.durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Big(Icons.Rounded.SkipPrevious, 92, Color.White.copy(alpha = 0.12f), Color.White) { pm.previous() }
                    Big(if (pm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 128, accent, Color.Black) { pm.togglePlay() }
                    Big(Icons.Rounded.SkipNext, 92, Color.White.copy(alpha = 0.12f), Color.White) { pm.next() }
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(14.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable { onExit() }.padding(12.dp)) {
            Icon(Icons.Rounded.Close, "Exit driving mode", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun Big(icon: androidx.compose.ui.graphics.vector.ImageVector, dp: Int, bg: Color, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(dp.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((dp * 0.55f).dp))
    }
}
