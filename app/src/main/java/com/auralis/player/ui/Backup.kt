package com.auralis.player.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.auralis.player.Graph
import com.auralis.player.data.Archive
import com.auralis.player.data.Playlist
import com.auralis.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ import (playlist import button + restore)
/** Returns a function that opens the file picker; a progress / result dialog is drawn by this composable. */
@Composable
fun rememberImporter(): () -> Unit {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Archive.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { status = "Reading…"; result = null; error = null; uri = u } }
    uri?.let { u ->
        LaunchedEffect(u) {
            try { result = Archive.read(ctx, u) { status = it } } catch (e: Throwable) { error = e.message ?: "Could not read that file" }
        }
        val r = result
        AlertDialog(
            onDismissRequest = { if (r != null || error != null) uri = null },
            title = { Text(if (r != null) "Imported" else if (error != null) "Import failed" else "Importing…") },
            text = {
                when {
                    error != null -> Text(error!!)
                    r != null -> Text("${r.playlists} playlist(s) · ${r.added} new song(s) added" +
                        (if (r.matched > 0) " · ${r.matched} already on this phone" else "") +
                        (if (r.missing > 0) "\n${r.missing} song(s) were only listed in the file (no audio inside) and weren't found on this phone." else ""))
                    else -> Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(10.dp)); Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
                }
            },
            confirmButton = { if (r != null || error != null) TextButton(onClick = { uri = null }) { Text("OK") } },
        )
    }
    return { pick.launch(arrayOf("*/*")) }
}

// ------------------------------------------------------------------ share a song / playlist
@Composable
fun ShareFlow(title: String, songs: List<Song>, playlist: Playlist?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var label by remember { mutableStateOf("Preparing…") }
    LaunchedEffect(Unit) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Offbeat" }
            File(dir, "$safe.offbeat")
        }
        val ok = runCatching {
            file.outputStream().use { out ->
                Archive.write(ctx, out, songs, listOfNotNull(playlist), songs.map { it.id }.toSet(), compressAudio = true) { p, t -> progress = p; label = t }
            }
        }.isSuccess
        if (ok) {
            val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share $title"))
        } else Toast.makeText(ctx, "Could not prepare the file", Toast.LENGTH_LONG).show()
        onDone()
    }
    AlertDialog(
        onDismissRequest = {}, title = { Text("Packing “$title”") },
        text = { Column { LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth()); Spacer(Modifier.height(10.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) } },
        confirmButton = {},
    )
}

// ------------------------------------------------------------------ backup / restore screen
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = Graph.store; val lib = Graph.library
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val pickedPl = remember { mutableStateMapOf<String, Boolean>() }
    val pickedSong = remember { mutableStateMapOf<String, Boolean>() }
    val sizes = remember { mutableStateMapOf<String, Long>() }
    var compress by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var label by remember { mutableStateOf("") }
    val importer = rememberImporter()
    val songs = lib.songs.sortedBy { it.title.lowercase() }

    LaunchedEffect(songs.size) {
        withContext(Dispatchers.IO) { songs.forEach { if (it.id !in sizes) sizes[it.id] = fileSizeOf(ctx, it) } }
    }
    fun plSize(p: Playlist) = p.songIds.sumOf { sizes[it] ?: 0L }
    val musicIds = songs.filter { pickedSong[it.id] == true }
    val musicBytes = musicIds.sumOf { sizes[it.id] ?: 0L }
    val chosenPl = store.playlists.filter { pickedPl[it.id] == true }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        running = true; progress = 0f; label = ""
        scope.launch {
            val index = (chosenPl.flatMap { p -> p.songIds.mapNotNull { lib.byId[it] } } + musicIds).distinctBy { it.id }
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(u)!!.use { out ->
                    Archive.write(ctx, out, index, chosenPl, musicIds.map { it.id }.toSet(), compress) { p, t -> progress = p; label = t }
                }
            }.isSuccess
            running = false
            Toast.makeText(ctx, if (ok) "Backup saved" else "Backup failed", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onBack() }
            Text("Backup & restore", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = importer) { Text("Restore") }
        }
        TabRow(tab, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = accent) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Playlist (${chosenPl.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Music (${musicIds.size})") })
        }
        if (tab == 0) LazyColumn(Modifier.weight(1f)) {
            if (store.playlists.isEmpty()) item { EmptyHint("No playlists yet.") }
            items(store.playlists, key = { it.id }) { p ->
                val on = pickedPl[p.id] == true
                fun set(v: Boolean) { pickedPl[p.id] = v; if (v) p.songIds.forEach { pickedSong[it] = true } }   // choosing a playlist ticks its songs in Music
                Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { set(it) })
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.songIds.size} songs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(humanSize(plSize(p)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else LazyColumn(Modifier.weight(1f)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { songs.forEach { pickedSong[it.id] = true } }) { Text("Select all") }
                    TextButton(onClick = { pickedSong.clear() }) { Text("Clear") }
                }
            }
            items(songs, key = { it.id }) { s ->
                val on = pickedSong[s.id] == true
                Row(Modifier.fillMaxWidth().clickable { pickedSong[s.id] = !on }.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { pickedSong[s.id] = it })
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(humanSize(sizes[s.id] ?: 0L), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compress audio (AAC 96 kbps)", fontSize = 14.sp)
                    Text(if (compress) "Smaller file, slower, slight quality loss" else "Original files, exact copy", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(compress, { compress = it })
            }
            Spacer(Modifier.height(8.dp))
            if (running) { LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth()); Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            else Button(
                onClick = { saver.launch("Offbeat-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".offbeat") },
                enabled = chosenPl.isNotEmpty() || musicIds.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            ) { Text("Back up · ${chosenPl.size} playlists · ${musicIds.size} songs · ${humanSize(musicBytes)}") }
        }
    }
}
