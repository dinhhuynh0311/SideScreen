package com.sidescreen.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Buffer-duration maths behind [AudioPlayer.TARGET_BUFFER_MS]. The sizing itself
 * lives in a private method that needs a real AudioTrack, so this pins the
 * arithmetic and the constants it depends on — the part that silently regressed
 * once already (a 2x getMinBufferSize buffer put audio 240ms behind the video).
 */
class AudioBufferSizeTest {
    @Test
    fun bytesPerFrameMatchesStereo16Bit() {
        assertEquals(4, AudioPlayer.BYTES_PER_FRAME)
    }

    @Test
    fun targetBufferConvertsToTheExpectedByteCount() {
        // 40ms at 48kHz stereo 16-bit = 1920 frames = 7680 bytes.
        val bytes = AudioPlayer.SAMPLE_RATE * AudioPlayer.TARGET_BUFFER_MS / 1000 * AudioPlayer.BYTES_PER_FRAME
        assertEquals(7680, bytes)
    }

    @Test
    fun bufferMsRoundTripsTheTarget() {
        assertEquals(AudioPlayer.TARGET_BUFFER_MS, AudioPlayer.bufferMs(7680))
    }

    @Test
    fun bufferMsReportsTheRegressionWeFixed() {
        // The old sizing: 2 x getMinBufferSize (23088) on this tablet.
        assertEquals(240, AudioPlayer.bufferMs(23088 * 2))
    }

    @Test
    fun matePadBurstRoundsUpToWholeBursts() {
        // 240-frame burst = 960 bytes; 7680 / 960 = 8 bursts exactly.
        val burstBytes = 240 * AudioPlayer.BYTES_PER_FRAME
        val targetBytes = AudioPlayer.SAMPLE_RATE * AudioPlayer.TARGET_BUFFER_MS / 1000 * AudioPlayer.BYTES_PER_FRAME
        assertEquals(0, targetBytes % burstBytes)
        assertEquals(8, targetBytes / burstBytes)
    }

    @Test
    fun awkwardBurstStillRoundsUpNotDown() {
        // A 700-frame (2800-byte) burst does not divide 7680: ceil gives 3
        // bursts (8400 bytes), never 2, so the HAL never gets a partial burst
        // and the buffer is never shorter than the target.
        val burstBytes = 700 * AudioPlayer.BYTES_PER_FRAME
        val targetBytes = AudioPlayer.SAMPLE_RATE * AudioPlayer.TARGET_BUFFER_MS / 1000 * AudioPlayer.BYTES_PER_FRAME
        val bursts = (targetBytes + burstBytes - 1) / burstBytes
        assertEquals(3, bursts)
        assertEquals(8400, bursts * burstBytes)
    }
}
