package com.sidescreen.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameSizeTest {
    @Test
    fun typicalFrameIsValid() {
        // 1024 frames x 2 channels x 2 bytes = 4096, a normal SCK callback.
        assertTrue(AudioPlayer.isValidAudioFrameSize(4096))
    }

    @Test
    fun zeroIsRejected() {
        assertFalse(AudioPlayer.isValidAudioFrameSize(0))
    }

    @Test
    fun negativeIsRejected() {
        // A misaligned stream can read a negative Int32; allocating on it throws.
        assertFalse(AudioPlayer.isValidAudioFrameSize(-1))
    }

    @Test
    fun absurdlyLargeIsRejected() {
        // Guard against a misaligned stream making us allocate ~2GB.
        assertFalse(AudioPlayer.isValidAudioFrameSize(Int.MAX_VALUE))
    }

    @Test
    fun boundaryIsAccepted() {
        assertTrue(AudioPlayer.isValidAudioFrameSize(AudioPlayer.MAX_AUDIO_FRAME_SIZE))
    }

    @Test
    fun justPastBoundaryIsRejected() {
        assertFalse(AudioPlayer.isValidAudioFrameSize(AudioPlayer.MAX_AUDIO_FRAME_SIZE + 1))
    }
}
