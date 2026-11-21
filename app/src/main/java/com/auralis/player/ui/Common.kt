package com.auralis.player.ui

import androidx.compose.material.icons.rounded.Share
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.player.Graph
import com.auralis.player.data.ArtLoader
import com.auralis.player.data.Song
import com.auralis.player.data.Sources
import java.text.Normalizer

// ---------------------------------------------------------------- helpers
fun fmt(ms: Long): String {
    val s = (ms.coerceAtLeast(0)) / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

private val marks = Regex("\\p{Mn}+")
fun norm(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").replace('đ', 'd').replace('Đ', 'D').lowercase()

fun sourceBadge(source: String): String = when (source) {
    Sources.YOUTUBE -> "YT"; Sources.TIKTOK -> "TT"; Sources.VIDEO -> "VID"; else -> ""
}

fun sourceColor(source: String): Color? = when (source) {
    Sources.YOUTUBE -> Color(0xFFFF5A5A)
    Sources.TIKTOK -> Color(0xFF2EE6E0)
    Sources.VIDEO -> Color(0xFFFFB84A)
    else -> null
}

// ---------------------------------------------------------------- artwork
@Composable
fun ArtImage(song: Song?, modifier: Modifier = Modifier, px: Int = 160, corner: Dp = 12.dp, big: Boolean = false) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(ArtLoader.peek(song, px), song?.id, px) {
        value = if (song == null) null else ArtLoader.load(ctx, song, px)
    }
    Box(modifier.clip(RoundedCornerShape(corner))) {
        Crossfade(targetState = bmp, animationSpec = tween(250), label = "art") { b ->
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else ArtPlaceholder(song, big)
        }
    }
}

@Composable
fun ArtPlaceholder(song: Song?, big: Boolean = false) {
    val h = ((song?.id?.hashCode() ?: 0).toUInt() % 360u).toFloat()
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color.hsv(h, 0.45f, 0.50f), Color.hsv((h + 45f) % 360f, 0.6f, 0.26f))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.fillMaxSize(if (big) 0.35f else 0.5f))
    }
}

@Composable
fun PlayingBars(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bars")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.4f, 0.9f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Row(modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(a, b, c).forEach { Box(Modifier.width(3.dp).fillMaxHeight(it).clip(RoundedCornerShape(2.dp)).background(color)) }
    }
}

// ---------------------------------------------------------------- song row
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    playing: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: @Composable () -> Unit,
    ghost: Boolean = false,
) {
    val ghostA by androidx.compose.animation.core.animateFloatAsState(if (ghost) 0f else 1f, androidx.compose.animation.core.tween(260), label = "ghost")
    val accent = LocalAccent.current
    val srcColor = sourceColor(song.source)
    val fav = Graph.store.isFav(song.id)
    val bg = if (selected) accent.copy(alpha = 0.16f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .alpha(ghostA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (srcColor != null) Box(Modifier.width(3.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(srcColor))
        if (srcColor != null) Spacer(Modifier.width(8.dp))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            ArtImage(song, Modifier.fillMaxSize(), 128, 10.dp)
            if (srcColor != null) {
                Text(
                    sourceBadge(song.source), color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(topStart = 6.dp, bottomEnd = 10.dp))
                        .background(srcColor).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (selectionMode) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (selected) accent else Color.White)
                }
            } else if (playing) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    PlayingBars(accent)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (srcColor != null && !song.title.startsWith("[")) {
                    Text("[${song.source}] ", color = srcColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when { playing -> accent; srcColor != null -> srcColor; else -> MaterialTheme.colorScheme.onSurface },
                    fontSize = 15.sp, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                "${song.artist} · ${fmt(song.durationMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
            )
        }
        if (fav) Icon(Icons.Rounded.Favorite, null, tint = accent, modifier = Modifier.size(16.dp))
        if (!selectionMode) trailing() else Spacer(Modifier.width(12.dp))
    }
}

// ---------------------------------------------------------------- 3-dot menu with the "playlists" panel sliding in on the right
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SongMenuButton(song: Song, playlistId: String? = null) {
    val store = Graph.store; val pm = Graph.player
    var open by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renamingFile by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { sub = false; open = true }) {
            Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(230.dp)) {
            AnimatedContent(
                targetState = sub, label = "menu",
                transitionSpec = {
                    if (targetState) (slideInHorizontally(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(220)) { -it / 3 } + fadeOut(tween(120)))
                    else (slideInHorizontally(tween(220)) { -it / 2 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(120)))
                },
            ) { showSub ->
                Column {
                    if (!showSub) {
                        DropdownMenuItem(
                            text = { Text("Add to playlist") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                            trailingIcon = { Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(18.dp)) },
                            onClick = { sub = true },
                        )
                        DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) },
                            onClick = { pm.playNext(song); open = false })
                        DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) },
                            onClick = { pm.addToQueue(listOf(song)); open = false })
                        DropdownMenuItem(
                            text = { Text(if (store.isFav(song.id)) "Remove favorite" else "Favorite") },
                            leadingIcon = { Icon(if (store.isFav(song.id)) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite, null) },
                            onClick = { store.toggleFav(song.id); open = false },
                        )
                        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = { open = false; sharing = true })
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { open = false; renaming = true })
                        if (song.id.startsWith("ms:")) DropdownMenuItem(text = { Text("Rename file") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { open = false; renamingFile = true })
                        DropdownMenuItem(text = { Text("Delete from device", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { open = false; deleting = true })
                        if (playlistId != null) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Remove from playlist") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { store.removeFromPlaylist(playlistId, song.id); open = false })
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().clickable { sub = false }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("Playlists", fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenuItem(
                            text = { Text("Create", color = LocalAccent.current, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Rounded.Add, null, tint = LocalAccent.current) },
                            onClick = { open = false; creating = true },
                        )
                        HorizontalDivider()
                        if (store.playlists.isEmpty()) Text("No playlists yet", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        store.playlists.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { if (song.id in p.songIds) Icon(Icons.Rounded.CheckCircle, null, tint = LocalAccent.current, modifier = Modifier.size(18.dp)) },
                                onClick = { store.addToPlaylist(p.id, listOf(song.id)); open = false },
                            )
                        }
                    }
                }
            }
        }
    }
    if (creating) NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
        val p = store.createPlaylist(name, listOf(song.id)); creating = false; p.id
    }
    if (renaming) NameDialog("Rename", song.title, "Save", onDismiss = { renaming = false }) { name ->
        store.renameSong(song.id, name); Graph.library.applyTitles(); renaming = false
    }
    if (deleting) DeleteFlow(listOf(song)) { deleting = false }
    if (sharing) ShareFlow(song.title, listOf(song), null) { sharing = false }
    if (renamingFile) RenameFileFlow(song) { renamingFile = false }
}

// ---------------------------------------------------------------- dialogs
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Any?) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PlaylistPickDialog(onDismiss: () -> Unit, onPick: (String) -> Unit, onCreate: (String) -> Unit) {
    val store = Graph.store
    var creating by remember { mutableStateOf(false) }
    if (creating) { NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { onCreate(it) }; return }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { creating = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, tint = LocalAccent.current); Spacer(Modifier.width(10.dp))
                    Text("Create", color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider()
                store.playlists.forEach { p ->
                    Text(p.name, Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 24.dp, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size)) }
}

@Composable fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

