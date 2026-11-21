package com.auralis.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/** .lrc lyrics. Looked up in: app storage (imported by the user) -> sibling file next to the audio (if readable). */
object LyricsRepo {
    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun fileFor(ctx: Context, s: Song) = File(dir(ctx), s.id.replace(Regex("[^A-Za-z0-9]"), "_") + ".lrc")

    suspend fun load(ctx: Context, s: Song): Lyrics? = withContext(Dispatchers.IO) {
        val own = fileFor(ctx, s)
        val text = when {
            own.exists() -> runCatching { own.readText() }.getOrNull()
            s.path != null -> {
                val base = s.path.substringBeforeLast('.')
                listOf("$base.lrc", "$base.LRC", "$base.txt").map(::File).firstOrNull { it.exists() && it.canRead() }
                    ?.let { runCatching { it.readText() }.getOrNull() }
            }
            else -> null
        }
        (text ?: EmbeddedLyrics.read(ctx, s))?.let { parse(it) }
    }

    suspend fun import(ctx: Context, s: Song, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val t = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            fileFor(ctx, s).writeText(t); true
        }.getOrDefault(false)
    }

    fun remove(ctx: Context, s: Song) { fileFor(ctx, s).delete() }

    private val offsetTag = Regex("^\\[offset:\\s*(-?\\d+)]", RegexOption.IGNORE_CASE)
    private val tag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(raw: String): Lyrics? {
        val text = raw.removePrefix("﻿")
        val out = ArrayList<LyricLine>()
        var offset = 0L
        val plain = ArrayList<String>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val off = offsetTag.find(l)
            if (off != null) { offset = off.groupValues[1].toLong(); continue }
            val tags = tag.findAll(l).toList()
            if (tags.isEmpty()) {
                if (!Regex("^\\[[a-zA-Z]+:.*]$").matches(l)) plain += l
                continue
            }
            val content = l.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
                val fr = m.groupValues[3]
                val ms = if (fr.isEmpty()) 0L else (fr.padEnd(3, '0').take(3)).toLong()
                out += LyricLine(min * 60_000 + sec * 1000 + ms + offset, content)
            }
        }
        if (out.isNotEmpty()) return Lyrics(out.sortedBy { it.timeMs }, true)
        if (plain.isNotEmpty()) return Lyrics(plain.map { LyricLine(0, it) }, false)
        return null
    }
}
