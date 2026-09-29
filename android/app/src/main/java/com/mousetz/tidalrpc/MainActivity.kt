package com.mousetz.tidalrpc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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
    private val canvas = Color.rgb(11, 11, 13)
    private val surface = Color.rgb(21, 21, 25)
    private val ink = Color.rgb(245, 245, 247)
    private val muted = Color.rgb(167, 167, 173)
    private val border = Color.rgb(41, 41, 47)
    private lateinit var permissionCard: View
    private lateinit var tidal: TextView
    private lateinit var discord: TextView
    private lateinit var track: TextView
    private lateinit var artist: TextView
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
            val side = dp(if (resources.configuration.screenWidthDp < 360) 16 else 20)
            setPadding(side, dp(28), side, dp(32))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            content.addView(this, LinearLayout.LayoutParams(
                if (resources.configuration.screenWidthDp > 540) dp(480) else -1, -2))
        }
        fun add(view: View, top: Int) {
            column.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        }

        add(label("TIDAL RPC", 28f, true), 0)
        add(label("Your TIDAL listening activity on Discord", 14f, color = muted), 4)

        val statusCard = card()
        tidal = statusRow(statusCard, "TIDAL")
        discord = statusRow(statusCard, "Discord")
        statusCard.addView(View(this).apply { setBackgroundColor(border) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(14) })
        track = label("Nothing playing", 20f, true)
        artist = label("Open TIDAL and start a track", 14f, color = muted)
        statusCard.addView(track)
        statusCard.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        login = actionButton("Sign in to TIDAL").apply {
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
        }
        logout = actionButton("Sign out of TIDAL", secondary = true).apply {
            setOnClickListener { AppRuntime.signOut() }
        }
        statusCard.addView(login, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        statusCard.addView(logout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        auth = label("", 13f, color = muted)
        statusCard.addView(auth, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        add(statusCard, 24)

        val access = card()
        access.addView(label("Playback access needed", 17f, true))
        access.addView(label("Allow notification access to read TIDAL playback on this phone.",
            14f, color = muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        access.addView(actionButton("Grant playback access").apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        access.addView(label("If Android blocks access, open App info → More → Allow restricted settings.",
            12f, color = muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        permissionCard = access
        add(access, 14)

        val settings = card()
        settings.addView(label("Settings", 18f, true))
        enabled = switchRow(settings, "Enable Discord Rich Presence", "Share the current track",
            { AppRuntime.state.value.enabled }) { AppRuntime.setEnabled(it) }
        artistImage = switchRow(settings, "Show artist image", "Use artist art when available",
            { AppRuntime.state.value.showArtist }) { AppRuntime.setShowArtist(it) }
        buttons = switchRow(settings, "Show listen buttons", "Add TIDAL and app links",
            { AppRuntime.state.value.showButtons }) { AppRuntime.setShowButtons(it) }
        add(settings, 14)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(canvas)
            isFillViewport = true
            clipToPadding = false
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
        permissionCard.visibility = if (value.permissionGranted) View.GONE else View.VISIBLE
        val tidalState = when {
            !value.permissionGranted -> "Access needed"
            value.tidalStatus == "TIDAL playing" -> "Playing"
            value.tidalStatus == "TIDAL paused" -> "Paused"
            value.tidalStatus == "Playback access disconnected" -> "Disconnected"
            value.tidalStatus == "Waiting for track metadata" -> "Loading"
            else -> "Waiting"
        }
        setChip(tidal, tidalState, tidalState == "Playing",
            tidalState == "Access needed" || tidalState == "Disconnected")
        val discordState = when (value.discordStatus) {
            "Connected" -> "Connected"
            "Updating Discord" -> "Updating"
            "Rich Presence disabled" -> "Disabled"
            "Presence cleared" -> "Idle"
            else -> "Waiting"
        }
        setChip(discord, discordState, discordState == "Connected", false)
        track.text = value.track?.title ?: "Nothing playing"
        artist.text = value.track?.artist ?: "Open TIDAL and start a track"
        auth.text = value.authStatus.ifEmpty {
            if (BuildConfig.TIDAL_CLIENT_ID.isBlank()) "TIDAL client ID needed for artwork and links" else ""
        }
        auth.visibility = if (auth.text.isEmpty()) View.GONE else View.VISIBLE
        enabled.isChecked = value.enabled
        artistImage.isChecked = value.showArtist
        buttons.isChecked = value.showButtons
        login.visibility = if (value.signedIn || BuildConfig.TIDAL_CLIENT_ID.isBlank()) View.GONE else View.VISIBLE
        logout.visibility = if (value.signedIn) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(value: String, size: Float, bold: Boolean = false, color: Int = ink) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun rounded(color: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(surface, border)
    }

    private fun statusRow(parent: LinearLayout, name: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
        }
        row.addView(label(name, 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val chip = label("Waiting", 12f, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        row.addView(chip)
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return chip
    }

    private fun setChip(chip: TextView, value: String, active: Boolean, error: Boolean) {
        chip.text = value
        val fill = when {
            active -> Color.rgb(18, 59, 43)
            error -> Color.rgb(68, 35, 38)
            value == "Disabled" -> Color.rgb(39, 39, 45)
            else -> Color.rgb(48, 48, 56)
        }
        chip.setTextColor(when {
            active -> Color.rgb(114, 230, 174)
            error -> Color.rgb(255, 139, 145)
            value == "Disabled" -> Color.rgb(140, 140, 148)
            else -> Color.rgb(199, 199, 205)
        })
        chip.background = rounded(fill)
    }

    private fun actionButton(value: String, secondary: Boolean = false) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (secondary) ink else canvas)
        background = RippleDrawable(
            ColorStateList.valueOf(if (secondary) border else Color.rgb(220, 220, 225)),
            rounded(if (secondary) surface else ink,
                if (secondary) Color.rgb(85, 85, 93) else null), null)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(48)
    }

    private fun switchRow(parent: LinearLayout, title: String, detail: String,
                          current: () -> Boolean, onChange: (Boolean) -> Unit): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(title, 14f, true))
        copy.addView(label(detail, 12f, color = muted))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
        val toggle = Switch(this).apply {
            text = ""
            contentDescription = title
            setOnCheckedChangeListener { _, checked -> if (checked != current()) onChange(checked) }
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return toggle
    }
}
