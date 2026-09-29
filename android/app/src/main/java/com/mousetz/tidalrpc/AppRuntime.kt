package com.mousetz.tidalrpc

import android.app.NotificationManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class AppUiState(
    val permissionGranted: Boolean = false,
    val tidalStatus: String = "Waiting for TIDAL",
    val discordStatus: String = "Waiting for TIDAL",
    val track: Track? = null,
    val enabled: Boolean = true,
    val showButtons: Boolean = false,
)

object AppRuntime {
    val state = MutableStateFlow(AppUiState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Application
    private var playback: Playback? = null

    @Synchronized fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext as Application
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        state.update {
            it.copy(
                enabled = prefs.getBoolean("enabled", true),
                showButtons = prefs.getBoolean("show_buttons", false),
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
            playback = next
            state.update {
                it.copy(
                    tidalStatus = status,
                    track = next?.track,
                    discordStatus = if (next == null) {
                        if (it.enabled) "Presence cleared" else "Rich Presence disabled"
                    } else it.discordStatus,
                )
            }
            publish()
        }
    }

    private fun publish() {
        val settings = state.value
        DiscordBridge.show(playback.takeIf { settings.enabled }, settings.showButtons)
    }

    fun setEnabled(value: Boolean) {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        state.update {
            it.copy(enabled = value, discordStatus = when {
                !value -> "Rich Presence disabled"
                playback == null -> "Presence cleared"
                else -> it.discordStatus
            })
        }
        publish()
    }

    fun setShowButtons(value: Boolean) {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("show_buttons", value).apply()
        state.update { it.copy(showButtons = value) }
        publish()
    }
}
