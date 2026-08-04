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
 * deliberately no ring buffer here. It is sized to [TARGET_BUFFER_MS], enough to
 * absorb the ~10ms a large video keyframe can add ahead of an audio packet on
 * the shared connection. Add a real jitter buffer only if dropouts show up on
 * WiFi.
 *
 * Do NOT size it from `getMinBufferSize`: on the MatePad Pro 12.2 that returns
 * 5772 frames (120ms) against a 240-frame (5ms) fast-mixer burst, and a 2x
 * multiple of it put audio ~240ms behind the video — plainly audible. The
 * device's native burst is the unit the HAL actually consumes, so a small
 * multiple of that is the low-latency size.
 *
 * @param nativeBurstFrames AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER, or 0
 *   when unavailable, in which case we fall back to the getMinBufferSize path.
 *
 * There is no A/V sync: audio plays as it arrives. Fine for music, calls, and
 * UI sound. Measured lag is constant rather than growing, so clock drift is not
 * accumulating; revisit only if that changes.
 */
class AudioPlayer(
    private val nativeBurstFrames: Int = 0,
) {
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
        val bufferBytes = preferredBufferBytes(minBytes)

        // RuntimeException rather than named subclasses: build() declares
        // UnsupportedOperationException ("Cannot create AudioTrack" — no free
        // output track, or the low-latency config refused), and AOSP reaches it
        // by converting an internal IllegalArgumentException, so the exact type
        // is an implementation detail that has already changed once. Anything
        // escaping here would unwind through play() and onAudioFrame into
        // receiveData, whose only catch is IOException, tearing down the *video*
        // stream over an audio fault. Catching the supertype keeps every failure
        // inside the startFailed latch: audio goes quiet, video keeps streaming.
        val built =
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
                    .setBufferSizeInBytes(bufferBytes)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } catch (e: RuntimeException) {
                // A burst-derived size is well above one HAL burst, so this
                // should not be a size rejection — but if the device refuses it,
                // working audio at higher latency beats no audio at all.
                Log.w(
                    TAG,
                    "AudioTrack rejected a ${bufferBytes}-byte buffer (${e.javaClass.simpleName}) " +
                        "— retrying at ${minBytes * 2}",
                    e,
                )
                startAtFallbackSize(minBytes)
                return
            }

        // Separate from build() so a play() failure still releases the native
        // output track we just took, instead of leaking it until finalization.
        try {
            built.play()
        } catch (e: RuntimeException) {
            Log.e(TAG, "AudioTrack.play() failed (${e.javaClass.simpleName}) — audio disabled", e)
            built.release()
            return
        }

        track = built
        Log.i(
            TAG,
            "AudioTrack started (buffer $bufferBytes bytes ≈ ${bufferMs(bufferBytes)}ms, " +
                "burst=$nativeBurstFrames frames, getMinBufferSize=$minBytes)",
        )
    }

    /**
     * Buffer size targeting [TARGET_BUFFER_MS], rounded up to a whole number of
     * HAL bursts so the fast mixer is never handed a partial burst.
     */
    private fun preferredBufferBytes(minBytes: Int): Int {
        if (nativeBurstFrames <= 0) return minBytes * 2
        val burstBytes = nativeBurstFrames * BYTES_PER_FRAME
        val targetBytes = SAMPLE_RATE * TARGET_BUFFER_MS / 1000 * BYTES_PER_FRAME
        val bursts = ((targetBytes + burstBytes - 1) / burstBytes).coerceAtLeast(2)
        return bursts * burstBytes
    }

    /** Last resort when the device rejects the burst-derived size. */
    private fun startAtFallbackSize(minBytes: Int) {
        val built =
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
            } catch (e: RuntimeException) {
                Log.e(TAG, "AudioTrack creation failed (${e.javaClass.simpleName}) — audio disabled", e)
                return
            }
        try {
            built.play()
        } catch (e: RuntimeException) {
            Log.e(TAG, "AudioTrack.play() failed (${e.javaClass.simpleName}) — audio disabled", e)
            built.release()
            return
        }
        track = built
        Log.i(TAG, "AudioTrack started at fallback buffer ${minBytes * 2} bytes ≈ ${bufferMs(minBytes * 2)}ms")
    }

    @Synchronized
    fun stop() {
        startFailed = false
        track?.let { t ->
            // Underruns are how we learn the buffer went too small on a device
            // whose burst differs from the MatePad's. Non-zero here plus audible
            // crackling means raise TARGET_BUFFER_MS.
            Log.i(TAG, "AudioTrack stopping (underruns=${t.underrunCount})")
            try {
                t.pause()
                t.flush()
                t.stop()
            } catch (e: RuntimeException) {
                // Same reasoning as start(): the usual case is a benign
                // "already stopped" IllegalStateException, but stop() runs on
                // the UI thread straight from the disconnect buttons with no
                // try/catch above it, so letting an unexpected type escape
                // would crash the app on a click. Swallowing also guarantees
                // the release() below still runs.
                Log.w(TAG, "AudioTrack teardown failed (${e.javaClass.simpleName})", e)
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

        /** 2 channels x 16-bit. */
        const val BYTES_PER_FRAME = 4

        /**
         * Playback buffer target. 40ms covers the ~10ms a large video keyframe
         * can add ahead of an audio packet on the shared TCP connection, and sits
         * close enough to the ~25ms video pipeline that the offset is not audible.
         * Raise it if [stop] starts reporting underruns.
         */
        const val TARGET_BUFFER_MS = 40

        fun bufferMs(bytes: Int): Int = bytes * 1000 / (SAMPLE_RATE * BYTES_PER_FRAME)

        /** 256 KB, about 1.4s of 48 kHz stereo — far above any real frame. */
        const val MAX_AUDIO_FRAME_SIZE = 1 shl 18

        /**
         * Guards against a misaligned stream making us allocate an absurd
         * buffer, or throw on a negative length.
         */
        fun isValidAudioFrameSize(size: Int): Boolean = size in 1..MAX_AUDIO_FRAME_SIZE
    }
}
