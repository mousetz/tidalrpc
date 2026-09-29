package com.mousetz.tidalrpc

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.notification.NotificationListenerService

class TidalListener : NotificationListenerService() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var manager: MediaSessionManager? = null
    private var selected: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var sessionsChanged: MediaSessionManager.OnActiveSessionsChangedListener? = null

    @Synchronized override fun onListenerConnected() {
        AppRuntime.initialize(this)
        AppRuntime.refreshPermission()
        if (thread != null) return
        thread = HandlerThread("TIDAL sessions").apply { start() }
        handler = Handler(thread!!.looper)
        manager = getSystemService(MediaSessionManager::class.java)
        sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { syncSessions() }
        try {
            manager!!.addOnActiveSessionsChangedListener(
                sessionsChanged!!, ComponentName(this, TidalListener::class.java), handler,
            )
            handler!!.post { syncSessions() }
        } catch (_: SecurityException) {
            stop()
        }
    }

    @Synchronized private fun syncSessions() {
        if (handler == null) return
        val sessions = try {
            manager?.getActiveSessions(ComponentName(this, TidalListener::class.java)).orEmpty()
                .filter { it.packageName == "com.aspiro.tidal" }
        } catch (_: SecurityException) {
            stop()
            return
        }
        val next = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null
        } ?: sessions.firstOrNull()
        if (selected?.sessionToken != next?.sessionToken) {
            selected?.let { old -> callback?.let { old.unregisterCallback(it) } }
            selected = next
            callback = if (next == null) null else object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = syncPlayback()
                override fun onPlaybackStateChanged(state: PlaybackState?) = syncSessions()
                override fun onSessionDestroyed() = syncSessions()
            }.also { next.registerCallback(it, handler) }
        }
        syncPlayback()
    }

    @Synchronized private fun syncPlayback() {
        if (handler == null) return
        val controller = selected ?: run {
            AppRuntime.onPlayback(null, "Waiting for TIDAL")
            return
        }
        val state = controller.playbackState
        if (state?.state != PlaybackState.STATE_PLAYING) {
            AppRuntime.onPlayback(null, "TIDAL paused")
            return
        }
        val metadata = controller.metadata ?: run {
            AppRuntime.onPlayback(null, "Waiting for track metadata")
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) {
            AppRuntime.onPlayback(null, "Waiting for track metadata")
            return
        }
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val track = Track(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().ifBlank { "Unknown artist" },
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            mediaId = mediaId,
            albumImage = metadataImageUrl(
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            ),
            url = tidalTrackUrl(mediaId),
        )
        AppRuntime.onPlayback(
            Playback(
                track,
                state.position.coerceAtLeast(0),
                state.lastPositionUpdateTime.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                state.playbackSpeed.takeIf { it.isFinite() && it > 0 } ?: 1f,
            ),
            "TIDAL playing",
        )
    }

    override fun onListenerDisconnected() = stop()

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    @Synchronized private fun stop() {
        if (thread == null) return
        selected?.let { old -> callback?.let { old.unregisterCallback(it) } }
        selected = null
        callback = null
        sessionsChanged?.let { manager?.removeOnActiveSessionsChangedListener(it) }
        sessionsChanged = null
        manager = null
        handler = null
        thread?.quitSafely()
        thread = null
        AppRuntime.onPlayback(null, "Playback access disconnected")
        AppRuntime.refreshPermission()
    }
}
