package com.mousetz.tidalrpc

import java.net.URI
import kotlin.math.abs

data class Track(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val mediaId: String?,
    val albumImage: String? = null,
    val url: String? = null,
) {
    val key: String get() = listOf(mediaId, title, artist, durationMs).joinToString("|")
}

data class Playback(
    val track: Track,
    val positionMs: Long,
    val updatedElapsedMs: Long,
    val speed: Float,
)

data class Timestamps(val startSeconds: Long, val endSeconds: Long?)

internal fun metadataImageUrl(vararg candidates: String?): String? {
    for (raw in candidates) {
        val value = raw?.trim().orEmpty()
        if (value.length !in 1..300) continue
        val uri = runCatching { URI(value) }.getOrNull() ?: continue
        if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) return value
    }
    return null
}

internal fun tidalTrackUrl(mediaId: String?): String? = mediaId
    ?.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
    ?.let { "https://tidal.com/browse/track/$it" }

internal fun positionAt(playback: Playback, nowElapsedMs: Long): Long {
    val elapsed = (nowElapsedMs - playback.updatedElapsedMs).coerceAtLeast(0)
    val position = playback.positionMs + (elapsed * playback.speed).toLong()
    return position.coerceIn(0, playback.track.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE)
}

internal fun timestamps(playback: Playback, nowElapsedMs: Long, nowEpochMs: Long): Timestamps {
    val position = positionAt(playback, nowElapsedMs)
    val start = (nowEpochMs - (position / playback.speed).toLong()) / 1000
    val duration = playback.track.durationMs?.takeIf { it > 0 }
    return Timestamps(start, duration?.let {
        (nowEpochMs + ((it - position) / playback.speed).toLong()) / 1000
    })
}

internal fun isNewMoment(previous: Playback?, current: Playback, nowElapsedMs: Long): Boolean {
    if (previous == null || previous.track.key != current.track.key) return true
    if (abs(previous.speed - current.speed) > 0.01f) return true
    return abs(positionAt(previous, nowElapsedMs) - positionAt(current, nowElapsedMs)) > 2_000
}
