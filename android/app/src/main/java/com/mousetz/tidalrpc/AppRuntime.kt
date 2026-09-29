package com.mousetz.tidalrpc

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class AppUiState(
    val permissionGranted: Boolean = false,
    val tidalStatus: String = "Waiting for TIDAL",
    val discordStatus: String = "Waiting for TIDAL",
    val track: Track? = null,
    val signedIn: Boolean = false,
    val authStatus: String = "",
    val enabled: Boolean = true,
    val showArtist: Boolean = false,
    val showButtons: Boolean = false,
)

object AppRuntime {
    val state = MutableStateFlow(AppUiState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private var catalog: TidalCatalog? = null
    private var playback: Playback? = null
    private var currentTrack: Track? = null
    private var lookupKey: String? = null
    private var lookup: Job? = null

    @Synchronized fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (BuildConfig.TIDAL_CLIENT_ID.isNotBlank()) catalog = TidalCatalog(app)
        state.update {
            it.copy(
                enabled = prefs.getBoolean("enabled", true),
                showArtist = prefs.getBoolean("show_artist", false),
                showButtons = prefs.getBoolean("show_buttons", false),
                signedIn = catalog?.signedIn == true,
            )
        }
        DiscordBridge.onStatus = { message -> state.update { it.copy(discordStatus = message) } }
        refreshPermission()
    }

    fun refreshPermission() {
        if (!::app.isInitialized) return
        val component = ComponentName(app, TidalListener::class.java)
        val granted = app.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(component)
        state.update { it.copy(permissionGranted = granted) }
        if (!granted) onPlayback(null, "Notification access needed")
    }

    fun onPlayback(next: Playback?, status: String) {
        scope.launch {
            if (next == null) {
                lookup?.cancel()
                lookup = null
                lookupKey = null
                playback = null
                currentTrack = null
                state.update {
                    it.copy(tidalStatus = status, track = null,
                        discordStatus = if (it.enabled) "Presence cleared" else "Rich Presence disabled")
                }
                DiscordBridge.clear()
                return@launch
            }
            playback = next
            val changed = lookupKey != next.track.key
            if (changed) {
                lookup?.cancel()
                lookupKey = next.track.key
                currentTrack = next.track
            }
            state.update { it.copy(tidalStatus = status, track = currentTrack) }
            publish()
            val source = catalog ?: return@launch
            if (!changed || !state.value.signedIn || !state.value.enabled) return@launch
            lookup = scope.launch(Dispatchers.IO) {
                var retry = false
                val enriched = try { withTimeout(10_000) { source.enrich(next.track) } }
                    catch (_: TimeoutCancellationException) { retry = true; null }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { retry = true; null }
                withContext(Dispatchers.Main) {
                    if (lookupKey != next.track.key || playback == null || enriched == null) return@withContext
                    currentTrack = enriched
                    state.update { it.copy(track = enriched) }
                    publish()
                }
                if (retry) {
                    delay(60_000)
                    withContext(Dispatchers.Main) {
                        if (lookupKey == next.track.key && playback != null) {
                            lookup = null
                            lookupKey = null
                            onPlayback(playback, "TIDAL playing")
                        }
                    }
                }
            }
        }
    }

    private fun publish() {
        val settings = state.value
        DiscordBridge.show(
            playback.takeIf { settings.enabled }, currentTrack.takeIf { settings.enabled },
            settings.showArtist, settings.showButtons,
        )
        if (!settings.enabled) state.update { it.copy(discordStatus = "Rich Presence disabled") }
    }

    fun setEnabled(value: Boolean) {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        state.update { it.copy(enabled = value) }
        if (!value) lookup?.cancel()
        publish()
        if (value && currentTrack?.url == null) {
            lookupKey = null
            playback?.let { onPlayback(it, "TIDAL playing") }
        }
    }

    fun setShowArtist(value: Boolean) {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("show_artist", value).apply()
        state.update { it.copy(showArtist = value) }
        publish()
    }

    fun setShowButtons(value: Boolean) {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("show_buttons", value).apply()
        state.update { it.copy(showButtons = value) }
        publish()
    }

    fun loginUrl(): Uri? = catalog?.loginUrl()

    fun finishLogin(uri: Uri) {
        val source = catalog ?: return
        val query = uri.encodedQuery ?: return
        scope.launch {
            val success = try { withContext(Dispatchers.IO) { source.finishLogin(query) } }
                catch (_: Exception) { false }
            state.update { it.copy(signedIn = success,
                authStatus = if (success) "TIDAL sign-in complete" else "TIDAL sign-in failed") }
            if (success) {
                lookupKey = null
                playback?.let { onPlayback(it, "TIDAL playing") }
            }
        }
    }

    fun signOut() {
        val source = catalog ?: return
        scope.launch {
            val signedOut = try { withContext(Dispatchers.IO) { source.logout() }; true }
                catch (_: Exception) { false }
            state.update { it.copy(signedIn = !signedOut && it.signedIn,
                authStatus = if (signedOut) "Signed out of TIDAL" else "TIDAL sign-out failed") }
            if (!signedOut) return@launch
            lookup?.cancel()
            lookup = null
            currentTrack = playback?.track
            state.update { it.copy(track = currentTrack) }
            publish()
        }
    }
}
