package com.auralis.player.data

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Listen history, stored as small as it can be:
 *  history.log  - 8 bytes per play: [uint32 unix-seconds][uint32 index into the dictionary]
 *  history.dict - one text line per distinct song: id \u0001 title \u0001 artist \u0001 source
 * so 10 000 plays are only ~80 KB and a song is described once, no matter how often it was played.
 */
object History {
    class Entry(val id: String, val title: String, val artist: String, val source: String)
    class Play(val time: Long, val entry: Entry)

    private lateinit var dir: File
    private val dict = ArrayList<Entry>()
    private val index = HashMap<String, Int>()
    private val sep = '\u0001'
    private val lock = Any()

    fun init(ctx: Context) {
        dir = ctx.filesDir
        synchronized(lock) {
            dict.clear(); index.clear()
            val f = File(dir, "history.dict")
            if (f.exists()) f.forEachLine { l ->
                val p = l.split(sep)
                if (p.size >= 4) { index[p[0]] = dict.size; dict.add(Entry(p[0], p[1], p[2], p[3])) }
            }
        }
    }

    private fun clean(s: String) = s.replace('\n', ' ').replace(sep, ' ')

    fun log(song: Song, timeMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        var i = index[song.id]
        if (i == null) {
            i = dict.size
            val e = Entry(song.id, clean(song.title), clean(song.artist), song.source)
            dict.add(e); index[song.id] = i
            File(dir, "history.dict").appendText("${e.id}$sep${e.title}$sep${e.artist}$sep${e.source}\n")
        }
        java.io.FileOutputStream(File(dir, "history.log"), true).use {
            it.write(ByteBuffer.allocate(8).putInt((timeMs / 1000).toInt()).putInt(i).array())
        }
    }

    fun count(): Int = synchronized(lock) { (File(dir, "history.log").takeIf { it.exists() }?.length() ?: 0L).toInt() / 8 }
    fun sizeBytes(): Long = synchronized(lock) { (File(dir, "history.log").takeIf { it.exists() }?.length() ?: 0L) + (File(dir, "history.dict").takeIf { it.exists() }?.length() ?: 0L) }

    /** all plays, oldest first (ints only, cheap) */
    fun readAll(): List<Play> = synchronized(lock) {
        val f = File(dir, "history.log"); if (!f.exists()) return emptyList()
        val n = (f.length() / 8).toInt(); val out = ArrayList<Play>(n)
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(8 * 4096); var left = n
            while (left > 0) {
                val take = minOf(left, 4096); r.readFully(buf, 0, take * 8)
                val bb = ByteBuffer.wrap(buf, 0, take * 8)
                repeat(take) { val t = bb.int.toLong() and 0xFFFFFFFFL; val i = bb.int; dict.getOrNull(i)?.let { e -> out.add(Play(t * 1000, e)) } }
                left -= take
            }
        }
        out
    }

    fun clear() = synchronized(lock) {
        File(dir, "history.log").delete(); File(dir, "history.dict").delete(); dict.clear(); index.clear()
    }
}
