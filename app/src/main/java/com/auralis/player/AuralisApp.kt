package com.auralis.player

import android.app.Application
import android.content.Context
import com.auralis.player.data.Downloader
import com.auralis.player.data.Library
import com.auralis.player.data.Store
import com.auralis.player.playback.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Tiny service-locator: everything lives for the whole process so the service, widget and UI share state. */
object Graph {
    lateinit var app: Context
    lateinit var store: Store
    lateinit var library: Library
    lateinit var player: PlayerManager
    lateinit var downloader: Downloader
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        store = Store(app)
        com.auralis.player.data.History.init(app)
        library = Library(app, store, scope)
        player = PlayerManager(app, store, library, scope)
        downloader = Downloader(app, store, library, scope)
    }
}

class AuralisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
