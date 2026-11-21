package com.auralis.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.scrollBy
import com.auralis.player.data.Folder
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.player.Graph
import com.auralis.player.data.Song
import com.auralis.player.data.SortMode
import kotlinx.coroutines.launch

fun sortSongs(songs: List<Song>, mode: SortMode, asc: Boolean, counts: Map<String, Int>, playlists: List<com.auralis.player.data.Playlist>, sourceFirst: String = ""): List<Song> {
    val ci = String.CASE_INSENSITIVE_ORDER
    val cmp: Comparator<Song> = when (mode) {
        SortMode.NAME -> compareBy(ci) { it.title }
        SortMode.DATE -> compareBy<Song> { it.dateAdded }.thenBy(ci) { it.title }
        SortMode.ARTIST -> compareBy<Song, String>(ci) { it.artist }.thenBy(ci) { it.title }
        SortMode.LENGTH -> compareBy<Song> { it.durationMs }.thenBy(ci) { it.title }
        SortMode.MOST -> compareBy<Song> { counts[it.id] ?: 0 }.thenBy(ci) { it.title }
        SortMode.SOURCE -> {
            val order = listOf("Youtube", "TikTok", "Video")
            // rank 0 = the chosen source, then the other downloads, then normal local files
            fun rank(sr: String): Int = when {
                sourceFirst.isNotEmpty() && sr == sourceFirst -> 0
                sr in order -> 1 + (if (sourceFirst.isEmpty()) order.indexOf(sr) else 0)
                else -> 10
            }
            compareBy<Song> { rank(it.source) }.thenBy(ci) { it.title }
        }
        SortMode.PLAYLIST -> {
            val first = HashMap<String, String>()
            playlists.sortedBy { it.name.lowercase() }.forEach { p -> p.songIds.forEach { first.putIfAbsent(it, p.name.lowercase()) } }
            compareBy<Song, String>(ci) { first[it.id] ?: "￿" }.thenBy(ci) { it.title }
        }
    }
    val sorted = songs.sortedWith(cmp)
    return if (asc) sorted else sorted.reversed()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen() {
    val store = Graph.store; val lib = Graph.library; val pm = Graph.player
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pickPlaylist by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    // ---- folders / drag state
    var openFolder by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var moveDialog by remember { mutableStateOf(false) }
    var deleteSongs by remember { mutableStateOf<List<Song>?>(null) }
    var selMenu by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var carrying by remember { mutableStateOf(false) }            // selected songs are being carried to a folder
    var fingerPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var hotKey by remember { mutableStateOf<String?>(null) }
    var carrySongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var carryStarts by remember { mutableStateOf<List<androidx.compose.ui.geometry.Offset>>(emptyList()) }
    var sweepBase by remember { mutableStateOf<Set<String>?>(null) }  // non-null while sweep-selecting
    var sweepAnchor by remember { mutableIntStateOf(0) }
    var edgeScroll by remember { mutableFloatStateOf(0f) }
    val folder = store.folders.firstOrNull { it.id == openFolder }
    LaunchedEffect(folder == null) { if (folder == null && openFolder != null) openFolder = null }
    androidx.activity.compose.BackHandler(folder != null && !selecting) { openFolder = null }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { lib.importUris(uris) }
    }

    val list = remember(lib.songs, store.sortMode, store.sortAsc, store.onlyFav, store.favorites, store.playCounts, store.playlists, store.sourceFirst, query, store.folders, openFolder) {
        var l = lib.songs
        if (folder != null) { val ids = folder.songIds.toSet(); l = l.filter { it.id in ids } }
        else if (query.isBlank()) { val filed = store.folders.flatMap { it.songIds }.toSet(); l = l.filter { it.id !in filed } }
        if (store.onlyFav) l = l.filter { it.id in store.favorites }
        if (query.isNotBlank()) { val q = norm(query); l = l.filter { norm(it.title).contains(q) || norm(it.artist).contains(q) || norm(it.album).contains(q) } }
        sortSongs(l, store.sortMode, store.sortAsc, store.playCounts, store.playlists, store.sourceFirst)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- top bar (changes while selecting)
        AnimatedContent(targetState = selecting, label = "topbar") { sel ->
            if (sel) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Rounded.Close, "Cancel") { selected = emptySet() }
                    Text("${selected.size} selected", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconBtn(Icons.Rounded.SelectAll, "All") { selected = list.map { it.id }.toSet() }
                    IconBtn(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { pickPlaylist = true }
                    IconBtn(Icons.Rounded.Folder, "Move to folder") { moveDialog = true }
                    IconBtn(Icons.Rounded.Delete, "Delete from device") { deleteSongs = list.filter { it.id in selected } }
                    Box {
                        IconBtn(Icons.Rounded.MoreVert, "More") { selMenu = true }
                        DropdownMenu(expanded = selMenu, onDismissRequest = { selMenu = false }) {
                            DropdownMenuItem(text = { Text("Favorite") }, leadingIcon = { Icon(Icons.Rounded.Favorite, null) },
                                onClick = { selMenu = false; store.setFav(selected, selected.any { it !in store.favorites }); selected = emptySet() })
                            DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) },
                                onClick = { selMenu = false; pm.addToQueue(list.filter { it.id in selected }); selected = emptySet() })
                            if (folder != null) DropdownMenuItem(text = { Text("Take out of folder") }, leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                                onClick = { selMenu = false; store.moveToFolder("root", selected); selected = emptySet() })
                        }
                    }
                }
            } else if (searching) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search title, artist, album") },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                    IconBtn(Icons.Rounded.Close, "Close") { query = ""; searching = false }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (folder != null) IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { openFolder = null }
                    Column(Modifier.weight(1f)) {
                        Text(folder?.name ?: "Music", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${list.size} songs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconBtn(Icons.Rounded.Search, "Search") { searching = true }
                    if (folder == null) IconBtn(Icons.Rounded.CreateNewFolder, "New folder") { newFolder = true }
                    IconBtn(Icons.Rounded.CloudDownload, "Add from link") { linkDialog = true }
                    IconBtn(Icons.Rounded.LibraryAdd, "Import") { importer.launch(arrayOf("audio/*", "application/ogg")) }
                    IconBtn(Icons.Rounded.Shuffle, "Shuffle all", tint = accent) { pm.shufflePlay(list, null) }
                }
            }
        }

        // ---- sort row
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                var sortMenu by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(20.dp), color = accent.copy(alpha = 0.16f),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).combinedClickable(
                        onClick = {   // tap = next sort mode
                            val next = SortMode.entries[(store.sortMode.ordinal + 1) % SortMode.entries.size]
                            store.setSort(next, next.defaultAsc)
                        },
                        onLongClick = { sortMenu = true },   // hold = pick from a list
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sort, null, tint = accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Sort: ${store.sortMode.label}", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortMode.entries.forEach { m ->
                        val on = m == store.sortMode
                        DropdownMenuItem(
                            text = { Text(m.label, color = if (on) accent else MaterialTheme.colorScheme.onSurface, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) },
                            trailingIcon = { if (on) Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(18.dp)) },
                            onClick = { store.setSort(m, if (on) store.sortAsc else m.defaultAsc); sortMenu = false },
                        )
                        if (m == SortMode.SOURCE) {
                            // the detail only shows here, the button itself just says "Sort: Source"
                            listOf("" to "All downloads first", "Youtube" to "Youtube first", "TikTok" to "TikTok first", "Video" to "Video first").forEach { (key, label) ->
                                val sel = on && store.sourceFirst == key
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 13.sp, color = if (sel) accent else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) },
                                    modifier = Modifier.padding(start = 20.dp),
                                    trailingIcon = { if (sel) Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(16.dp)) },
                                    onClick = { store.changeSourceFirst(key); store.setSort(SortMode.SOURCE, true); sortMenu = false },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            IconBtn(if (store.sortAsc) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, "Direction", tint = accent, size = 20.dp) {
                store.setSort(store.sortMode, !store.sortAsc)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable { store.changeOnlyFav(!store.onlyFav) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = store.onlyFav, onCheckedChange = { store.changeOnlyFav(it) })
                Text("Only Favorited", fontSize = 13.sp)
            }
        }

        DownloadsBar()

        // ---- list
        val showFolderRows = query.isBlank() && lib.loaded
        fun keyAt(y: Float): String? {
            val li = listState.layoutInfo
            val yy = y.coerceIn(li.viewportStartOffset.toFloat(), (li.viewportEndOffset - 1).toFloat())
            return li.visibleItemsInfo.firstOrNull { yy >= it.offset && yy < it.offset + it.size }?.key as? String
        }
        fun applySweep(pos: androidx.compose.ui.geometry.Offset) {
            val base = sweepBase ?: return
            val key = keyAt(pos.y) ?: return
            val idx = list.indexOfFirst { it.id == key }; if (idx < 0) return
            val lo = minOf(sweepAnchor, idx); val hi = maxOf(sweepAnchor, idx)
            selected = base + list.subList(lo, hi + 1).map { it.id }
        }
        fun edgeFor(y: Float): Float {
            val zone = with(density) { 84.dp.toPx() }; val h = listState.layoutInfo.viewportEndOffset.toFloat()
            val max = with(density) { 14.dp.toPx() }
            return when { y < zone -> -max * ((zone - y) / zone).coerceIn(0f, 1f); y > h - zone -> max * ((y - (h - zone)) / zone).coerceIn(0f, 1f); else -> 0f }
        }
        LaunchedEffect(Unit) {
            while (true) {
                if (edgeScroll != 0f) { listState.scrollBy(edgeScroll); if (sweepBase != null) applySweep(fingerPos); if (carrying) hotKey = keyAt(fingerPos.y)?.takeIf { it.startsWith("f:") } }
                kotlinx.coroutines.delay(16)
            }
        }

        if (!lib.loaded) EmptyHint("Scanning your music…")
        else if (list.isEmpty() && (folder != null || lib.songs.isEmpty() || store.folders.isEmpty())) {
            if (folder != null) { LazyColumn { item { FolderRow("All music", 0, hotKey == "f:root", up = true, onClick = { openFolder = null }) } }; EmptyHint("This folder is empty.\nSelect songs, then drag them onto the folder or use Move to folder.") }
            else EmptyHint(if (lib.songs.isEmpty()) "No music found.\nUse the import button to add mp3 / ogg / wav files." else "Nothing matches.")
        }
        else Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().longPressDrag(
                    onStart = { pos ->
                        val key = keyAt(pos.y)
                        if (key == null || key.startsWith("f:")) false
                        else {
                            val idx = list.indexOfFirst { it.id == key }
                            if (idx < 0) false
                            else {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                fingerPos = pos
                                if (key in selected) {   // carry the whole selection to a folder
                                    val songs = list.filter { it.id in selected }
                                    carrySongs = songs.take(8)
                                    carryStarts = carrySongs.map { s ->
                                        val it = listState.layoutInfo.visibleItemsInfo.firstOrNull { v -> v.key == s.id }
                                        if (it != null) androidx.compose.ui.geometry.Offset(with(density) { 40.dp.toPx() }, it.offset + it.size / 2f) else pos
                                    }
                                    carrying = true; hotKey = null
                                } else {                 // sweep-select
                                    sweepBase = selected; sweepAnchor = idx
                                    selected = selected + key
                                }
                                true
                            }
                        }
                    },
                    onMove = { pos ->
                        fingerPos = pos
                        edgeScroll = edgeFor(pos.y)
                        if (carrying) hotKey = keyAt(pos.y)?.takeIf { it.startsWith("f:") }
                        else applySweep(pos)
                    },
                    onEnd = { pos, cancelled ->
                        if (carrying) {
                            val target = if (cancelled) null else keyAt(pos.y)?.takeIf { it.startsWith("f:") }
                            if (target != null) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                store.moveToFolder(target.removePrefix("f:"), selected); selected = emptySet()
                            }
                        }
                        carrying = false; hotKey = null; sweepBase = null; edgeScroll = 0f
                    },
                ),
                state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
            ) {
                if (showFolderRows && folder != null) item(key = "f:root") { FolderRow("All music", 0, hotKey == "f:root", up = true, onClick = { openFolder = null }) }
                if (showFolderRows && folder == null) items(store.folders, key = { "f:" + it.id }) { f ->
                    FolderRow(
                        f.name, f.songIds.size, hotKey == "f:" + f.id, onClick = { openFolder = f.id },
                        onRename = { renameFolder = f }, onDelete = { store.deleteFolder(f.id) },
                    )
                }
                items(list, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        playing = pm.current?.id == song.id && pm.sourcePlaylistId == null,
                        selected = song.id in selected,
                        selectionMode = selecting,
                        onClick = {
                            if (selecting) selected = if (song.id in selected) selected - song.id else selected + song.id
                            else pm.playSongNow(song, list, null)
                        },
                        onLongClick = {},   // long-press is handled by the list gesture (select + sweep / carry)
                        trailing = { SongMenuButton(song) },
                        ghost = carrying && song.id in selected,
                    )
                }
            }
            if (carrying && carrySongs.isNotEmpty()) SnakeOverlay(carrySongs, carryStarts, selected.size) { fingerPos }
        }
    }

    if (linkDialog) LinkDialog { linkDialog = false }
    if (newFolder) NameDialog("New folder", "", "Create", onDismiss = { newFolder = false }) { name -> store.createFolder(name); newFolder = false }
    renameFolder?.let { f -> NameDialog("Rename folder", f.name, "Save", onDismiss = { renameFolder = null }) { name -> store.renameFolder(f.id, name); renameFolder = null } }
    if (moveDialog) FolderPickDialog(
        onDismiss = { moveDialog = false },
        onPick = { fid -> store.moveToFolder(fid, selected); moveDialog = false; selected = emptySet() },
        onCreate = { name -> store.createFolder(name, selected); moveDialog = false; selected = emptySet() },
    )
    deleteSongs?.let { ds -> DeleteFlow(ds) { deleteSongs = null; selected = emptySet() } }

    if (pickPlaylist) {
        val ids = selected.toList()
        PlaylistPickDialog(
            onDismiss = { pickPlaylist = false },
            onPick = { pid -> store.addToPlaylist(pid, ids); pickPlaylist = false; selected = emptySet() },
            onCreate = { name -> store.createPlaylist(name, ids); pickPlaylist = false; selected = emptySet() },
        )
    }
}


@Composable
fun LinkDialog(onDismiss: () -> Unit) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var whole by remember { mutableStateOf(true) }
    var text by remember {
        val c = clip.getText()?.text.orEmpty()
        mutableStateOf(if (Regex("https?://\\S+").containsMatchIn(c)) c.trim() else "")
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add music from a link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YouTube, TikTok, or any direct video / audio link. The audio is extracted and added to your list with its cover and author.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it }, placeholder = { Text("https://…") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(whole, { whole = it })
                    Text("Playlist link: download every song and make a playlist", fontSize = 13.sp)
                }
                androidx.compose.material3.TextButton(onClick = { clip.getText()?.text?.let { text = it } }) { Text("Paste") }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { if (Graph.downloader.enqueue(text, whole) > 0) onDismiss() }) { Text("Download") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DownloadsBar() {
    val jobs = Graph.downloader.jobs.filter { !it.hidden }
    if (jobs.isEmpty()) return
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        jobs.forEach { j ->
            val failed = j.state == com.auralis.player.data.Downloader.State.FAILED
            Surface(shape = RoundedCornerShape(14.dp), color = (if (failed) MaterialTheme.colorScheme.error else accent).copy(alpha = 0.12f)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.title.ifBlank { j.url }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (failed || j.elapsedSec == 0 || j.state == com.auralis.player.data.Downloader.State.DONE) j.message else "${j.message} · ${j.elapsedSec}s", fontSize = 12.sp, maxLines = 2, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (failed || j.state == com.auralis.player.data.Downloader.State.DONE)
                            IconBtn(Icons.Rounded.Close, "Dismiss", size = 18.dp) { Graph.downloader.dismiss(j.id) }
                    }
                    if (!failed && j.state != com.auralis.player.data.Downloader.State.DONE) {
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        if (j.progress > 0f) androidx.compose.material3.LinearProgressIndicator(progress = { j.progress }, Modifier.fillMaxWidth(), color = accent)
                        else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
                    }
                }
            }
        }
    }
}
