package com.auralis.player.ui

import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.player.Graph
import com.auralis.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

fun fileSizeOf(ctx: android.content.Context, s: Song): Long =
    runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(s.uri), "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)

private val srcPrefix = Regex("^\\[(Youtube|TikTok|Video)] ")
private val copySuffix = Regex("(\\s*\\(\\d+\\)|\\s*[-_ ]\\s*copy( \\(\\d+\\))?)$", RegexOption.IGNORE_CASE)
private fun normTitle(t: String): String {
    var s = t.lowercase().replace(srcPrefix, "").trim()
    while (copySuffix.containsMatchIn(s)) s = s.replace(copySuffix, "").trim()
    return s.replace(Regex("[^\\p{L}\\p{N}]+"), "")
}

/** One "keep" copy + the extra copies of the same song (same title, length within 2 s). */
class DupGroup(val keep: Song, val extras: List<Song>)

fun findDuplicates(all: List<Song>): List<DupGroup> {
    val store = Graph.store
    fun score(s: Song) = (store.playCounts[s.id] ?: 0) + (if (s.id in store.favorites) 5 else 0) +
        store.playlists.count { s.id in it.songIds } * 3 + store.folders.count { s.id in it.songIds }
    val out = ArrayList<DupGroup>()
    all.filter { it.durationMs > 0 }.groupBy { normTitle(it.title) }.filter { it.key.isNotEmpty() && it.value.size > 1 }.values.forEach { same ->
        val left = same.sortedBy { it.durationMs }.toMutableList()
        while (left.isNotEmpty()) {
            val first = left.removeAt(0)
            val cluster = mutableListOf(first)
            val it2 = left.iterator()
            while (it2.hasNext()) { val x = it2.next(); if (abs(x.durationMs - first.durationMs) <= 2000) { cluster += x; it2.remove() } }
            if (cluster.size > 1) {
                val keep = cluster.sortedWith(compareByDescending<Song> { score(it) }.thenBy { it.dateAdded }).first()
                out += DupGroup(keep, cluster.filter { it !== keep })
            }
        }
    }
    return out.sortedBy { it.keep.title.lowercase() }
}

@Composable
fun DuplicatesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    var groups by remember { mutableStateOf<List<DupGroup>?>(null) }
    val sizes = remember { mutableStateMapOf<String, Long>() }
    val checked: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
    var deleting by remember { mutableStateOf<List<Song>?>(null) }
    var rescan by remember { mutableIntStateOf(0) }
    LaunchedEffect(rescan) {
        try {
            if (rescan > 0) Graph.library.refresh()          // let the library forget deleted files first
            val g = withContext(Dispatchers.Default) { findDuplicates(Graph.library.songs) }
            val found = withContext(Dispatchers.IO) { HashMap<String, Long>().also { m -> g.forEach { d -> (d.extras + d.keep).forEach { m[it.id] = fileSizeOf(ctx, it) } } } }
            sizes.putAll(found)
            checked.clear(); g.forEach { d -> d.extras.forEach { checked[it.id] = true } }   // everything ticked at first
            groups = g
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { groups = emptyList() }
    }
    val all = groups?.flatMap { it.extras }.orEmpty()
    val selected = all.filter { checked[it.id] == true }
    val bytes = selected.sumOf { sizes[it.id] ?: 0L }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onBack() }
            Column(Modifier.weight(1f)) {
                Text("Duplicate songs", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (groups == null) "Searching…" else "${all.size} extra copies in ${(groups?.size ?: 0)} groups", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            groups == null -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = accent) }
            all.isEmpty() -> Box0 { EmptyHint("No duplicates found. Nice and clean.") }
            else -> LazyColumn(Modifier.weight(1f)) {
                (groups ?: emptyList()).forEach { g ->
                    item(key = "h-" + g.keep.id) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ArtImage(g.keep, Modifier.size(40.dp), 128, 8.dp); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(g.keep.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text("Keeping this one · ${humanSize(sizes[g.keep.id] ?: 0L)}", fontSize = 12.sp, color = accent)
                            }
                        }
                    }
                    items(g.extras, key = { it.id }) { s ->
                        val on = checked[s.id] == true
                        Row(Modifier.fillMaxWidth().clickable { checked[s.id] = !on }.padding(start = 40.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, { checked[s.id] = it })
                            Column(Modifier.weight(1f)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text(listOfNotNull(s.source, s.path?.substringBeforeLast('/')?.substringAfterLast('/')).joinToString(" · "), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(humanSize(sizes[s.id] ?: 0L), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (all.isNotEmpty()) Button(
            onClick = { deleting = selected }, enabled = selected.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Delete ${selected.size} ${if (selected.size == 1) "file" else "files"} · ${humanSize(bytes)}") }
    }
    deleting?.let { d -> DeleteFlow(d) { deleting = null; groups = null; rescan++ } }
}

@Composable
private fun Box0(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
