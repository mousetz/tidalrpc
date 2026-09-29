package com.mousetz.tidalrpc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
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
    private lateinit var connectionHint: TextView
    private lateinit var enabled: Switch
    private lateinit var buttons: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
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
                if (resources.configuration.screenWidthDp >= 600) dp(480) else -1, -2))
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
        connectionHint = label("", 13f, color = muted)
        statusCard.addView(connectionHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        add(statusCard, 24)

        val access = card()
        access.addView(label("Playback access needed", 17f, true))
        access.addView(label("Allow notification access to read TIDAL playback on this phone.",
            14f, color = muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        access.addView(actionButton("Grant playback access").apply {
            setOnClickListener {
                val component = ComponentName(this@MainActivity, TidalListener::class.java)
                val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                }
                try { startActivity(detail) }
                catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                catch (_: SecurityException) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        access.addView(label("If Android blocks access, open App info → More → Allow restricted settings.",
            12f, color = muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        permissionCard = access
        add(access, 14)

        val settings = card()
        settings.addView(label("Settings", 18f, true))
        enabled = switchRow(settings, "Enable Discord Rich Presence", "Share the current track",
            { AppRuntime.state.value.enabled }) { AppRuntime.setEnabled(it) }
        buttons = switchRow(settings, "Show listen buttons", "Add links when available",
            { AppRuntime.state.value.showButtons }) { AppRuntime.setShowButtons(it) }
        add(settings, 14)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(canvas)
            isFillViewport = true
            clipToPadding = false
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        collection = scope.launch { AppRuntime.state.collect(::render) }
    }

    override fun onResume() {
        super.onResume()
        AppRuntime.refreshPermission()
    }

    override fun onDestroy() {
        collection?.cancel()
        super.onDestroy()
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
        artist.text = value.track?.artist ?: when (value.tidalStatus) {
            "TIDAL paused" -> "Resume playback in TIDAL"
            "Waiting for track metadata" -> "Waiting for song details"
            "Playback access disconnected" -> "Reopen TIDAL RPC to reconnect"
            else -> "Open TIDAL and start a track"
        }
        connectionHint.text = if (value.track != null && value.enabled &&
            value.discordStatus == "Waiting for Discord") "Open Discord; TIDAL RPC will retry automatically." else ""
        connectionHint.visibility = if (connectionHint.text.isEmpty()) View.GONE else View.VISIBLE
        enabled.isChecked = value.enabled
        buttons.isChecked = value.showButtons
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

    private fun actionButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(canvas)
        background = RippleDrawable(
            ColorStateList.valueOf(Color.rgb(220, 220, 225)), rounded(ink), null)
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
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        copy.addView(label(title, 14f, true))
        copy.addView(label(detail, 12f, color = muted))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
        val toggle = Switch(this).apply {
            text = ""
            contentDescription = "$title. $detail"
            setOnCheckedChangeListener { _, checked -> if (checked != current()) onChange(checked) }
        }
        row.addView(toggle)
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return toggle
    }
}
