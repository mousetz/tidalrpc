package com.mousetz.tidalrpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {
    private val track = Track("Song", "Artist", "Album", 180_000, "123")

    @Test fun timestampsClampAndAdvance() {
        val playback = Playback(track, 30_000, 1_000, 1f)
        assertEquals(40_000, positionAt(playback, 11_000))
        assertEquals(Timestamps(970, 1_150), timestamps(playback, 11_000, 1_010_000))
        assertEquals(180_000, positionAt(playback, 999_000))
        assertEquals(Timestamps(985, 1_075),
            timestamps(playback.copy(speed = 2f), 11_000, 1_010_000))
    }

    @Test fun onlySeeksAndTrackChangesNeedAnotherUpdate() {
        val before = Playback(track, 30_000, 1_000, 1f)
        assertFalse(isNewMoment(before, Playback(track, 40_000, 11_000, 1f), 11_000))
        assertTrue(isNewMoment(before, Playback(track, 80_000, 11_000, 1f), 11_000))
        assertTrue(isNewMoment(before, Playback(track.copy(mediaId = "456"), 0, 11_000, 1f), 11_000))
        assertTrue(isNewMoment(before, Playback(track, 40_000, 11_000, 2f), 11_000))
    }
}
