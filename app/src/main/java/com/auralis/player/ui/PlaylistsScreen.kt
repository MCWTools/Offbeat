package com.auralis.player.ui

import androidx.compose.material.icons.rounded.Share
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.player.Graph
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.TrendingUp
import com.auralis.player.data.History
import com.auralis.player.data.Auto
import com.auralis.player.data.Song

@Composable
fun PlaylistsScreen() {
    val store = Graph.store; val lib = Graph.library
    var openId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val importer = rememberImporter()
    val autos = Auto.playlists(store, lib.songs)
    val open = (autos + store.playlists).firstOrNull { it.id == openId }

    if (showHistory) { BackHandler { showHistory = false }; HistoryScreen { showHistory = false }; return }
    if (open != null) {
        BackHandler { openId = null }
        PlaylistDetail(open.id) { openId = null }
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playlists", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("${store.playlists.size} playlists", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.OutlinedButton(onClick = importer, shape = RoundedCornerShape(22.dp)) { Text("Import") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { creating = true }, shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Create")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(autos, key = { it.id }) { p ->
                val first = p.songIds.firstNotNullOfOrNull { lib.byId[it] }
                Row(Modifier.fillMaxWidth().clickable { openId = p.id }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp)) {
                        ArtImage(first, Modifier.fillMaxSize(), 160, 14.dp)
                        Icon(if (p.id == Auto.FAV) Icons.Rounded.Favorite else Icons.Rounded.TrendingUp, null, tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp).clip(CircleShape).background(LocalAccent.current).padding(2.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Auto · ${p.songIds.size} songs", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "history") {
                Row(Modifier.fillMaxWidth().clickable { showHistory = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(LocalAccent.current.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.History, null, tint = LocalAccent.current) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Listen history", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${History.count()} plays", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (store.playlists.isEmpty()) item(key = "empty") { Text("No playlists yet. Tap Create to make your first one.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
            items(store.playlists, key = { it.id }) { p ->
                val first = p.songIds.firstNotNullOfOrNull { lib.byId[it] }
                val count = p.songIds.count { it in lib.byId }
                Row(Modifier.fillMaxWidth().clickable { openId = p.id }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtImage(first, Modifier.size(56.dp), 160, 14.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$count songs", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (creating) NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
        val p = store.createPlaylist(name); creating = false; openId = p.id; p
    }
}

@Composable
fun PlaylistDetail(id: String, onBack: () -> Unit) {
    val store = Graph.store; val lib = Graph.library; val pm = Graph.player
    val isAuto = Auto.isAuto(id)
    val p = (if (isAuto) Auto.playlists(store, lib.songs).firstOrNull { it.id == id } else store.playlists.firstOrNull { it.id == id }) ?: run { onBack(); return }
    val songs = remember(p.songIds, lib.songs) { p.songIds.mapNotNull { lib.byId[it] } }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val accent = LocalAccent.current

    if (adding) {
        BackHandler { adding = false }
        SongPicker(p.songIds.toSet(), onDone = { picked -> store.addToPlaylist(id, picked); adding = false }, onCancel = { adding = false })
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onBack() }
            Column(Modifier.weight(1f)) {
                Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${songs.size} songs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconBtn(Icons.Rounded.Share, "Share") { if (songs.isNotEmpty()) sharing = true }
            if (!isAuto) { IconBtn(Icons.Rounded.Edit, "Rename") { renaming = true }
            IconBtn(Icons.Rounded.Delete, "Delete") { deleting = true } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if (songs.isNotEmpty()) pm.playQueue(songs, 0, id) }, enabled = songs.isNotEmpty(), shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text("Play")
            }
            OutlinedButton(onClick = { pm.shufflePlay(songs, id) }, enabled = songs.isNotEmpty(), shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Shuffle")
            }
            Spacer(Modifier.weight(1f))
            if (!isAuto) OutlinedButton(onClick = { adding = true }, shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
            }
        }
        if (songs.isEmpty()) EmptyHint(if (isAuto) "Nothing here yet.\nIt fills itself as you listen / favorite songs longer than 30 s." else "This playlist is empty.\nTap Add and pick songs from your music.")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(songs, key = { it.id }) { s ->
                SongRow(
                    s, playing = pm.current?.id == s.id && pm.sourcePlaylistId == id, selected = false, selectionMode = false,
                    onClick = { pm.playSongNow(s, songs, id) }, onLongClick = {},
                    trailing = { SongMenuButton(s, playlistId = if (isAuto) null else id) },
                )
            }
        }
    }
    if (sharing) ShareFlow(p.name, songs, p) { sharing = false }
    if (renaming) NameDialog("Rename playlist", p.name, "Save", onDismiss = { renaming = false }) { store.renamePlaylist(id, it); renaming = false; null }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, title = { Text("Delete playlist?") },
        text = { Text("\"${p.name}\" will be removed. Your songs stay on the device.") },
        confirmButton = { TextButton(onClick = { deleting = false; store.deletePlaylist(id); onBack() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}

/** Full-screen multi-select picker: choose songs from the music list to add. */
@Composable
fun SongPicker(already: Set<String>, onDone: (List<String>) -> Unit, onCancel: () -> Unit) {
    val lib = Graph.library; val store = Graph.store
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    val accent = LocalAccent.current
    val list = remember(lib.songs, query) {
        val base = sortSongs(lib.songs, com.auralis.player.data.SortMode.NAME, true, emptyMap(), emptyList())
        if (query.isBlank()) base else { val q = norm(query); base.filter { norm(it.title).contains(q) || norm(it.artist).contains(q) } }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onCancel() }
            Text("Add songs", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { onDone(picked) }, enabled = picked.isNotEmpty(), shape = RoundedCornerShape(22.dp), modifier = Modifier.padding(end = 12.dp)) { Text("Add (${picked.size})") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(28.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { s ->
                val inList = s.id in already
                val on = s.id in picked
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !inList) { picked = if (on) picked - s.id else picked + s.id }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtImage(s, Modifier.size(44.dp), 128, 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, color = if (inList) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Text(if (inList) "Already in playlist" else s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (on || inList) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (inList) 0.5f else 0.9f),
                    )
                }
            }
        }
    }
}
