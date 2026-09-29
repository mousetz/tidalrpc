package com.mousetz.tidalrpc

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

object DiscordBridge {
    init { System.loadLibrary("tidalrpc_bridge") }

    private external fun nativeStart(appId: Long)
    private external fun nativeUpdate(
        title: String, artist: String, album: String?, largeImage: String?, smallImage: String?,
        startMs: Long, endMs: Long, url: String?, showButtons: Boolean,
    )
    private external fun nativePump(): Int
    private external fun nativeClear()

    private val thread = HandlerThread("Discord RPC").apply { start() }
    private val handler = Handler(thread.looper)
    private var active: Playback? = null
    private var shown: Track? = null
    private var lastPublished: Playback? = null
    private var lastOptions = ""
    private var retryMs = 5_000L
    private var nextRetry = 0L
    private var lastSentAt = 0L
    private var connected = false
    var onStatus: (String) -> Unit = {}

    private val pump = object : Runnable {
        override fun run() {
            if (active == null) return
            val response = nativePump()
            when (response) {
                1 -> {
                    onStatus("Connected")
                    connected = true
                    retryMs = 5_000
                    nextRetry = 0
                }
                -1 -> {
                    if (connected || nextRetry == 0L) onStatus("Waiting for Discord")
                    connected = false
                    if (nextRetry == 0L) nextRetry = SystemClock.elapsedRealtime() + retryMs
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (response == 0 && now - lastSentAt >= 10_000) {
                connected = false
                onStatus("Waiting for Discord")
                if (nextRetry == 0L) nextRetry = now + retryMs
            }
            var sent = false
            if (!connected && nextRetry > 0 && now >= nextRetry) {
                nextRetry = now + retryMs
                retryMs = (retryMs * 2).coerceAtMost(60_000)
                send()
                sent = true
            } else if (connected && now - lastSentAt >= 120_000) {
                send()
                sent = true
            }
            val delay = when {
                sent || (response == 0 && nextRetry == 0L) -> 1_000L
                connected -> 30_000L
                else -> (nextRetry - SystemClock.elapsedRealtime()).coerceAtLeast(1_000L)
            }
            handler.postDelayed(this, delay)
        }
    }

    fun show(playback: Playback?, track: Track?, showArtist: Boolean, showButtons: Boolean) {
        handler.post {
            if (playback == null || track == null) {
                clearOnThread()
                return@post
            }
            val options = "$showArtist|$showButtons"
            val visibleTrack = track.copy(artistImage = track.artistImage.takeIf { showArtist })
            val changed = isNewMoment(lastPublished, playback, SystemClock.elapsedRealtime()) ||
                visibleTrack != shown || options != lastOptions
            active = playback
            shown = visibleTrack
            lastOptions = options
            if (changed) send(showButtons)
            handler.removeCallbacks(pump)
            handler.post(pump)
        }
    }

    private fun send(showButtons: Boolean = lastOptions.endsWith("true")) {
        val playback = active ?: return
        val track = shown ?: return
        nativeStart(1411287876062416908L)
        val time = timestamps(playback, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        nativeUpdate(
            track.title, track.artist, track.album, track.albumImage, track.artistImage,
            time.startSeconds * 1_000, (time.endSeconds ?: 0) * 1_000,
            track.url, showButtons,
        )
        lastPublished = playback
        lastSentAt = SystemClock.elapsedRealtime()
        onStatus("Updating Discord")
    }

    fun clear() = handler.post { clearOnThread() }

    private fun clearOnThread() {
        handler.removeCallbacks(pump)
        if (active != null) nativeClear()
        active = null
        shown = null
        lastPublished = null
        connected = false
        nextRetry = 0
        retryMs = 5_000
        lastSentAt = 0
    }
}
