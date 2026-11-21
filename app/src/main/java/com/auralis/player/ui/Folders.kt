package com.auralis.player.ui

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.auralis.player.Graph
import com.auralis.player.data.Folder
import com.auralis.player.data.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ------------------------------------------------------------------ long-press then drag (sweep-select / carry selection)

/**
 * After a long press, keeps tracking the finger (consuming it, so the list does not scroll) until release.
 * onStart(pos) returns false to ignore the gesture. onEnd(pos, cancelled).
 */
@Composable
fun Modifier.longPressDrag(
    onStart: (Offset) -> Boolean,
    onMove: (Offset) -> Unit,
    onEnd: (Offset, Boolean) -> Unit,
): Modifier {
    val s = rememberUpdatedState(onStart); val m = rememberUpdatedState(onMove); val e = rememberUpdatedState(onEnd)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val res = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var stop = false
                while (!stop) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed || (c.position - down.position).getDistance() > slop) stop = true
                }
                true
            }
            if (res != null) return@awaitEachGesture   // released / scrolled before the long-press time
            if (!s.value(down.position)) return@awaitEachGesture
            var last = down.position
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id }
                if (c == null) { e.value(last, true); return@awaitEachGesture }
                c.consume()
                last = c.position
                if (!c.pressed) { e.value(last, false); return@awaitEachGesture }
                m.value(last)
            }
        }
    }
}

// ------------------------------------------------------------------ snake of covers following the finger

@Composable
fun SnakeOverlay(songs: List<Song>, starts: List<Offset>, total: Int, finger: () -> Offset) {
    val density = LocalDensity.current
    val tile = 46.dp
    val half = with(density) { tile.toPx() / 2f }
    val trail = remember { ArrayList<Pair<Long, Offset>>() }
    var now by remember { mutableLongStateOf(0L) }
    val gather = remember { Animatable(0f) }
    LaunchedEffect(Unit) { gather.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameMillis { t ->
            now = t; trail.add(t to finger())
            while (trail.size > 2 && trail[1].first < t - 1200) trail.removeAt(0)
        }
    }
    fun at(t: Long): Offset {
        if (trail.isEmpty()) return finger()
        if (t <= trail.first().first) return trail.first().second
        for (k in 1 until trail.size) {
            val (t1, p1) = trail[k]
            if (t1 >= t) {
                val (t0, p0) = trail[k - 1]
                val f = if (t1 == t0) 1f else (t - t0).toFloat() / (t1 - t0)
                return Offset(p0.x + (p1.x - p0.x) * f, p0.y + (p1.y - p0.y) * f)
            }
        }
        return trail.last().second
    }
    val g = gather.value
    Box(Modifier.fillMaxSize()) {
        songs.indices.reversed().forEach { i ->      // draw the tail first so the head ends on top
            val follow = at(now - i * 70L)
            val st = starts.getOrNull(i) ?: follow
            val x = st.x + (follow.x - st.x) * g
            val y = st.y + (follow.y - st.y) * g
            Box(
                Modifier.offset { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) }
                    .size(tile).zIndex(-i.toFloat())
                    .graphicsLayer {
                        val sc = 1f - 0.045f * i * g
                        scaleX = sc; scaleY = sc; rotationZ = ((i % 3) - 1) * 5f * g; alpha = 1f - 0.07f * i
                        shadowElevation = 14f; shape = RoundedCornerShape(10.dp); clip = false
                    },
            ) { ArtImage(songs[i], Modifier.fillMaxSize(), 128, 10.dp) }
        }
        if (total > 1) {
            val head = at(now)
            Text(
                "$total", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.offset { IntOffset((head.x + half * 0.5f).roundToInt(), (head.y - half * 1.4f).roundToInt()) }
                    .zIndex(10f).clip(CircleShape).background(LocalAccent.current).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ folder row (also the drop target)

@Composable
fun FolderRow(
    name: String, count: Int, hot: Boolean, up: Boolean = false,
    onClick: () -> Unit, onRename: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
) {
    val accent = LocalAccent.current
    val sc by animateFloatAsState(if (hot) 1.035f else 1f, tween(160), label = "hot")
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().scale(sc).padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (hot) accent.copy(alpha = 0.28f) else accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.Folder, null, tint = accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (up) "Drop here to take songs out of the folder" else "$count songs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRename != null && onDelete != null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Folder menu", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete folder (songs stay)") }, onClick = { menu = false; onDelete() })
            }
        } else Spacer(Modifier.width(12.dp))
    }
}

@Composable
fun FolderPickDialog(onDismiss: () -> Unit, onPick: (String) -> Unit, onCreate: (String) -> Unit) {
    val store = Graph.store
    var creating by remember { mutableStateOf(false) }
    if (creating) { NameDialog("New folder", "", "Create", onDismiss = { creating = false }) { onCreate(it) }; return }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, tint = LocalAccent.current); Spacer(Modifier.width(10.dp))
                    Text("New folder", color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                }
                store.folders.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null); Spacer(Modifier.width(10.dp)); Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ delete from device (3 s confirm)

@Composable
fun DeleteFlow(songs: List<Song>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val store = Graph.store; val lib = Graph.library
    val scope = rememberCoroutineScope()
    var secs by remember { mutableIntStateOf(3) }
    var launched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (secs > 0) { delay(1000); secs-- } }

    val ms = songs.filter { it.id.startsWith("ms:") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) store.forgetSongs(ms.map { it.id }.toSet())
        scope.launch { lib.refresh() }
        onDone()
    }

    fun confirm() {
        val dl = songs.filter { it.id.startsWith("dl:") }
        val other = songs.filter { !it.id.startsWith("ms:") && !it.id.startsWith("dl:") }
        dl.forEach { Graph.downloader.deleteDownload(it) }
        var failedOther = 0
        other.forEach { s -> if (!runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(s.uri)) }.getOrDefault(false)) failedOther++ }
        store.forgetSongs((dl + other).map { it.id }.toSet())
        if (failedOther > 0) Toast.makeText(ctx, "$failedOther imported file(s) could not be deleted from storage (no write access); removed from the list.", Toast.LENGTH_LONG).show()
        if (ms.isEmpty()) { scope.launch { lib.refresh() }; onDone(); return }
        val uris = ms.map { Uri.parse(it.uri) }
        if (Build.VERSION.SDK_INT >= 30) {
            launched = true
            launcher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender).build())
        } else {
            var failed = 0
            val ok = mutableSetOf<String>()
            ms.forEach { s -> if (runCatching { ctx.contentResolver.delete(Uri.parse(s.uri), null, null) }.getOrDefault(0) > 0) ok += s.id else failed++ }
            store.forgetSongs(ok)
            if (failed > 0) Toast.makeText(ctx, "$failed file(s) could not be deleted", Toast.LENGTH_LONG).show()
            scope.launch { lib.refresh() }; onDone()
        }
    }

    if (!launched) AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Delete permanently?") },
        text = {
            Text(
                (if (songs.size == 1) "\"${songs[0].title}\"" else "${songs.size} songs") +
                    " will be deleted from your device, not just from the list. This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = secs == 0) {
                Text(if (secs > 0) "Confirm ($secs)" else "Confirm", color = if (secs == 0) MaterialTheme.colorScheme.error else Color.Unspecified, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
