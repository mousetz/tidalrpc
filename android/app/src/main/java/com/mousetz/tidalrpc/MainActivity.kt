package com.mousetz.tidalrpc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.discord.socialsdk.DiscordSocialSdkInit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var collection: Job? = null
    private lateinit var permission: TextView
    private lateinit var tidal: TextView
    private lateinit var discord: TextView
    private lateinit var track: TextView
    private lateinit var auth: TextView
    private lateinit var login: Button
    private lateinit var logout: Button
    private lateinit var enabled: Switch
    private lateinit var artistImage: Switch
    private lateinit var buttons: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiscordSocialSdkInit.setEngineActivity(this)
        AppRuntime.initialize(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val space = (20 * resources.displayMetrics.density).toInt()
            setPadding(space, space, space, space)
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
            content.addView(this)
        }
        label("TIDAL RPC", 28f)
        label("Share music from the TIDAL app on this phone with Discord.")
        permission = label("")
        Button(this).apply {
            text = "Grant playback access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            content.addView(this)
        }
        label("On sideloaded Android 13+ devices, you may first need App info → More → Allow restricted settings.", 13f)
        tidal = label("")
        discord = label("")
        track = label("")
        auth = label("")

        enabled = Switch(this).apply {
            text = "Enable Rich Presence"
            setOnCheckedChangeListener { _, value ->
                if (value != AppRuntime.state.value.enabled) AppRuntime.setEnabled(value)
            }
            content.addView(this)
        }
        artistImage = Switch(this).apply {
            text = "Show artist image"
            setOnCheckedChangeListener { _, value ->
                if (value != AppRuntime.state.value.showArtist) AppRuntime.setShowArtist(value)
            }
            content.addView(this)
        }
        buttons = Switch(this).apply {
            text = "Show TIDAL and app buttons"
            setOnCheckedChangeListener { _, value ->
                if (value != AppRuntime.state.value.showButtons) AppRuntime.setShowButtons(value)
            }
            content.addView(this)
        }

        login = Button(this).apply {
            text = "Sign in to TIDAL for artwork and links"
            setOnClickListener {
                val url = try { AppRuntime.loginUrl() } catch (_: Exception) { null }
                if (url == null) {
                    Toast.makeText(this@MainActivity, "TIDAL sign-in could not start", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No browser available", Toast.LENGTH_LONG).show()
                }
            }
            content.addView(this)
        }
        logout = Button(this).apply {
            text = "Sign out of TIDAL"
            setOnClickListener { AppRuntime.signOut() }
            content.addView(this)
        }
        setContentView(ScrollView(this).apply {
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        })
        handleRedirect(intent)
        collection = scope.launch { AppRuntime.state.collect(::render) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        AppRuntime.refreshPermission()
    }

    override fun onDestroy() {
        collection?.cancel()
        super.onDestroy()
    }

    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "com.mousetz.tidalrpc" && uri.host == "oauth" && uri.path == "/callback") {
            AppRuntime.finishLogin(uri)
            setIntent(Intent())
        }
    }

    private fun render(value: AppUiState) {
        permission.text = if (value.permissionGranted) "Playback access: granted" else "Playback access: needed"
        tidal.text = "TIDAL: ${value.tidalStatus}"
        discord.text = "Discord: ${value.discordStatus}"
        track.text = value.track?.let { "${it.title} — ${it.artist}" } ?: "No track playing"
        auth.text = value.authStatus.ifEmpty {
            if (BuildConfig.TIDAL_CLIENT_ID.isBlank()) "TIDAL client ID needed for artwork and links" else ""
        }
        enabled.isChecked = value.enabled
        artistImage.isChecked = value.showArtist
        buttons.isChecked = value.showButtons
        login.visibility = if (value.signedIn || BuildConfig.TIDAL_CLIENT_ID.isBlank()) View.GONE else View.VISIBLE
        logout.visibility = if (value.signedIn) View.VISIBLE else View.GONE
    }
}
