package com.auralis.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.player.Graph
import com.auralis.player.data.History
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val lib = Graph.library; val pm = Graph.player
    val accent = LocalAccent.current
    var all by remember { mutableStateOf<List<History.Play>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var sortKey by remember { mutableIntStateOf(0) }      // 0 date, 1 name, 2 source
    var asc by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(60) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(version) { all = withContext(Dispatchers.IO) { History.readAll() } }
    val sorted = remember(all, sortKey, asc) {
        val base = when (sortKey) {
            0 -> all.sortedBy { it.time }
            1 -> all.sortedWith(compareBy<History.Play> { it.entry.title.lowercase() }.thenBy { it.time })
            else -> all.sortedWith(compareBy<History.Play> { it.entry.source }.thenBy { it.time })
        }
        if (asc) base else base.reversed()
    }
    LaunchedEffect(sortKey, asc) { shown = 60 }
    val state = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { val l = state.layoutInfo; l.totalItemsCount > 0 && (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 8 } }
    LaunchedEffect(nearEnd, sorted.size) { if (nearEnd && shown < sorted.size) shown += 60 }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onBack() }
            Column(Modifier.weight(1f)) {
                Text("Listen history", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${all.size} plays · ${humanSize(History.sizeBytes())} on disk", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconBtn(Icons.Rounded.DeleteSweep, "Clear history") { confirmClear = true }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Date", "Name", "Source").forEachIndexed { i, n -> FilterChip(selected = sortKey == i, onClick = { sortKey = i; asc = (i != 0) }, label = { Text(n) }) }
            Spacer(Modifier.weight(1f))
            IconBtn(if (asc) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, "Direction", accent) { asc = !asc }
        }
        if (all.isEmpty()) EmptyHint("Nothing here yet.\nA song is logged once you have listened to half of it.")
        else LazyColumn(Modifier.fillMaxSize(), state = state) {
            itemsIndexed(sorted.take(shown), key = { i, p -> "$i-${p.time}-${p.entry.id}" }) { _, p ->
                val song = lib.byId[p.entry.id]
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = song != null) { song?.let { pm.playSongNow(it, listOf(it), null) } }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtImage(song, Modifier.size(44.dp), 128, 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(listOf(p.entry.artist.takeIf { it.isNotBlank() && it != "<unknown>" }, p.entry.source).filterNotNull().joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(fmt.format(Date(p.time)), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("This removes all ${all.size} entries and frees ${humanSize(History.sizeBytes())}. Play counts and the auto playlists are not affected.") },
        confirmButton = { TextButton(onClick = { History.clear(); version++; confirmClear = false }) { Text("Clear", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
