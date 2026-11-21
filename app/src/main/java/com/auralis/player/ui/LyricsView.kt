package com.auralis.player.ui

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.player.data.Lyrics
import com.auralis.player.data.LyricsRepo
import com.auralis.player.data.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Synced lyrics. Played lines white, current line bigger + bold, upcoming lines grey.
 * Auto-scrolls to the current line, but leaves the list alone while the user is scrolling it (resumes ~3s later).
 * Tapping a line seeks to it.
 */
@Composable
fun LyricsView(song: Song, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val lyrics by produceState<Lyrics?>(null, song.id, version) { value = LyricsRepo.load(ctx, song) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { LyricsRepo.import(ctx, song, uri); version++ }
    }
    val l = lyrics
    if (l == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No lyrics for this song", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            Text("Import a .lrc file (synced) or a .txt", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import lyrics") }
        }
        return
    }

    val idx = if (l.synced) l.lines.indexOfLast { it.timeMs <= positionMs } else -1
    val state = rememberLazyListState()
    var lastUser by remember { mutableLongStateOf(0L) }
    val conn = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) lastUser = SystemClock.elapsedRealtime()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(idx, lastUser, song.id) {
        if (!l.synced || idx < 0) return@LaunchedEffect
        val wait = 3000 - (SystemClock.elapsedRealtime() - lastUser)
        if (wait > 0) delay(wait)
        val vh = state.layoutInfo.viewportSize.height
        state.animateScrollToItem(idx + 1, -(vh / 2) + 70)
    }

    val white = Color.White
    val grey = Color.White.copy(alpha = 0.32f)
    LazyColumn(modifier.fillMaxSize().nestedScroll(conn), state = state, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Box(Modifier.height(140.dp)) }
        itemsIndexed(l.lines) { i, line ->
            val current = l.synced && i == idx
            val past = l.synced && i < idx
            val color by animateColorAsState(if (!l.synced) white.copy(alpha = 0.85f) else if (current || past) white else grey, tween(350), label = "lc")
            val scale by animateFloatAsState(if (current) 1.14f else 1f, tween(350), label = "ls")
            Text(
                line.text.ifBlank { "♪" },
                color = color, fontSize = 22.sp, fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Bold, lineHeight = 29.sp,
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0.5f) }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = l.synced) { onSeek(line.timeMs) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 120.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Change lyrics", color = Color.White.copy(alpha = 0.6f)) }
                TextButton(onClick = { LyricsRepo.remove(ctx, song); version++ }) { Text("Remove", color = Color.White.copy(alpha = 0.6f)) }
            }
        }
    }
}
