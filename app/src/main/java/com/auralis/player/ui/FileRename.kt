package com.auralis.player.ui

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.auralis.player.Graph
import com.auralis.player.data.Song
import kotlinx.coroutines.launch

/** Renames the real file (display name) and its title tag in MediaStore, after the system write-permission prompt. */
@Composable
fun RenameFileFlow(song: Song, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val ext = song.path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
    fun apply(name: String): Boolean {
        val file = if (ext != null && !name.endsWith(".$ext", true)) "$name.$ext" else name
        val v = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, file); put(MediaStore.Audio.Media.TITLE, name.substringBeforeLast('.', name)) }
        return runCatching { ctx.contentResolver.update(Uri.parse(song.uri), v, null, null) }.getOrDefault(0) > 0
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val n = pending
        if (r.resultCode == Activity.RESULT_OK && n != null) {
            if (!apply(n)) Toast.makeText(ctx, "Could not rename the file", Toast.LENGTH_LONG).show()
            Graph.store.renameSong(song.id, n.substringBeforeLast('.', n))
        }
        scope.launch { Graph.library.refresh() }
        onDone()
    }
    val base = (song.path?.substringAfterLast('/')?.substringBeforeLast('.')) ?: song.title
    NameDialog("Rename file", base, "Rename", onDismiss = onDone) { name ->
        if (!song.id.startsWith("ms:")) { Toast.makeText(ctx, "Only files in your device storage can be renamed", Toast.LENGTH_LONG).show(); onDone(); return@NameDialog null }
        pending = name
        if (Build.VERSION.SDK_INT >= 30) {
            launcher.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(ctx.contentResolver, listOf(Uri.parse(song.uri))).intentSender).build())
        } else {
            if (apply(name)) Graph.store.renameSong(song.id, name) else Toast.makeText(ctx, "Could not rename the file", Toast.LENGTH_LONG).show()
            scope.launch { Graph.library.refresh() }; onDone()
        }
        null
    }
}
