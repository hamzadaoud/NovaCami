package com.novacami.app

import android.content.Context

/**
 * One StreamEngine for the whole app process. It no longer belongs to the screen: the foreground service keeps it
 * (and the camera) alive when the screen is off or the app is in the background, and the activity just attaches to it.
 */
object EngineHost {
    const val PORT = 8080
    @Volatile private var engine: StreamEngine? = null

    /** Call on the main thread. */
    @Synchronized
    fun get(ctx: Context): StreamEngine = engine ?: StreamEngine(ctx.applicationContext, PORT).also { engine = it }

    @Synchronized
    fun shutdown() { engine?.shutdown(); engine = null }
}
