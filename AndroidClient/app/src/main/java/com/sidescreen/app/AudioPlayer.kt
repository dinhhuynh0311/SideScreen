package com.sidescreen.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays the Mac's system audio: 48 kHz stereo signed 16-bit LE PCM, written
 * straight from the network receive thread.
 *
 * ponytail: AudioTrack's own buffer is the jitter buffer — there is
 * deliberately no ring buffer here. Buffer is 2x the device minimum, which is
 * enough to absorb the ~10ms a large video keyframe can add ahead of an audio
 * packet on the shared connection. Add a real jitter buffer only if dropouts
 * show up on WiFi.
 *
 * There is no A/V sync: audio plays as it arrives. Fine for music, calls, and
 * UI sound; may drift slightly against video.
 */
class AudioPlayer {
    private var track: AudioTrack? = null
    private var startFailed = false

    /**
     * Writes PCM, starting the track on first use. Synchronized against
     * [stop] because writes come from the network thread while teardown comes
     * from the main thread, and writing to a released AudioTrack throws.
     */
    @Synchronized
    fun play(
        pcm: ByteArray,
        length: Int,
    ) {
        if (track == null) {
            if (startFailed) return
            start()
            if (track == null) {
                startFailed = true
                return
            }
        }
        track?.write(pcm, 0, length, AudioTrack.WRITE_BLOCKING)
    }

    private fun start() {
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        if (minBytes <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBytes — audio playback unavailable")
            return
        }

        track =
            try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build(),
                    ).setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBytes * 2)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                    .also { it.play() }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack creation failed", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "AudioTrack rejected the format", e)
                null
            }

        if (track != null) {
            Log.i(TAG, "AudioTrack started (buffer ${minBytes * 2} bytes)")
        }
    }

    @Synchronized
    fun stop() {
        startFailed = false
        track?.let { t ->
            try {
                t.pause()
                t.flush()
                t.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack already stopped", e)
            }
            t.release()
        }
        track = null
    }

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 256 KB, about 1.4s of 48 kHz stereo — far above any real frame. */
        const val MAX_AUDIO_FRAME_SIZE = 1 shl 18

        /**
         * Guards against a misaligned stream making us allocate an absurd
         * buffer, or throw on a negative length.
         */
        fun isValidAudioFrameSize(size: Int): Boolean = size in 1..MAX_AUDIO_FRAME_SIZE
    }
}
