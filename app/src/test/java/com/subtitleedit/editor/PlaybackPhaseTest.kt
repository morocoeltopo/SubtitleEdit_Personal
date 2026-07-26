package com.subtitleedit.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPhaseTest {
    @Test
    fun onlyReadyPhaseCanAccessMediaPlayerState() {
        assertFalse(PlaybackPhase.IDLE.canAccessPlayer)
        assertFalse(PlaybackPhase.LOADING.canAccessPlayer)
        assertTrue(PlaybackPhase.READY.canAccessPlayer)
        assertFalse(PlaybackPhase.ERROR.canAccessPlayer)
        assertFalse(PlaybackPhase.RELEASED.canAccessPlayer)
    }
}
