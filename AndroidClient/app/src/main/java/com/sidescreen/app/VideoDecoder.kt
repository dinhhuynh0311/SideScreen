package com.sidescreen.app

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private fun diagLog(msg: String) = DiagLog.log("VD", msg)

class VideoDecoder(
    private val surface: Surface,
    private val display: Display? = null,
    initialWidth: Int = 1920,
    initialHeight: Int = 1200,
    // Exposed so MainActivity can detect a codec-negotiation/decoder mismatch
    // and recreate the decoder (see MainActivity.onStreamCodecSelected).
    val mime: String = MediaFormat.MIMETYPE_VIDEO_HEVC,
) {
    private var decoder: MediaCodec? = null
    private var decoderThread: HandlerThread? = null
    private var decoderHandler: Handler? = null

    private var frameCount = 0L
    private var droppedFrames = 0L
    private var staleOutputDrops = 0L
    private var lastStatsTime = System.currentTimeMillis()
    private var inputFrameCount = 0L
    private var outputFrameCount = 0L

    /** Queued into / emerged from the codec. A widening gap means a backlog. */
    val inputCount: Long get() = inputFrameCount
    val outputCount: Long get() = outputFrameCount

    // Decoder pipeline latency (input enqueue -> output buffer available),
    // accumulated over ~60 frames then logged. High values indicate the codec
    // is queuing frames internally (compose/present can't keep up downstream),
    // which surfaces to the user as input lag on the captured display.
    private var latencySumNs: Long = 0
    private var inputWaitSumNs: Long = 0
    private var inputWaitSamples: Long = 0
    private var keyframeLatencySumNs: Long = 0
    private var keyframeLatencySamples: Long = 0

    /**
     * PTS of frames queued as keyframes, consumed when the matching output
     * arrives. Bounded: entries are removed on match, and a decoder that never
     * emits a queued frame would otherwise leak, so it is capped below.
     */
    private val keyframePtsUs = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var latencySamples: Int = 0
    private var latencyMaxNs: Long = 0

    private val frameTimes = ArrayDeque<Long>(120)

    private val displayRefreshRate = display?.refreshRate ?: 60f

    private var currentWidth = initialWidth
    private var currentHeight = initialHeight

    @Volatile private var isRunning = false

    @Volatile private var needsKeyframe = true

    private var lastKeyframeRequestNs = 0L

    var onFrameRendered: ((Long) -> Unit)? = null
    var onFrameStats: ((fps: Double, variance: Double) -> Unit)? = null
    var onFrameDecoded: ((ByteArray) -> Unit)? = null
    var onKeyframeRequired: ((force: Boolean, reason: String) -> Unit)? = null

    /** Fired once when the decoder has accepted many frames but never output any —
     *  the black-screen-with-live-stats signature (stream above the device's
     *  decode limit, or an unusable decoder). Counts only frames actually queued
     *  to MediaCodec, so pre-keyframe drops on a slow start can't trigger it. */
    var onDecoderStalled: (() -> Unit)? = null
    private var stallReported = false
    private var queuedInputCount = 0L

    // Available input buffer indices — fed by onInputBufferAvailable callback.
    // Blocking queue rather than ConcurrentLinkedQueue so a caller can wait a
    // bounded time for the pool to refill; see INPUT_BUFFER_WAIT_MS.
    private val availableInputBuffers = LinkedBlockingQueue<Int>()

    init {
        setupDecoder()
    }

    fun updateResolution(
        width: Int,
        height: Int,
    ) {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            release()
            setupDecoder()
            requestKeyframe("resolution changed", force = true)
        }
    }

    private fun setupDecoder() {
        decoderThread = HandlerThread("DecoderThread", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        decoderHandler = Handler(decoderThread!!.looper)

        // Find a decoder that supports our resolution (prefer HW, fallback to SW)
        val decoderName = findBestDecoder(currentWidth, currentHeight)
        diagLog("setupDecoder: ${currentWidth}x$currentHeight, decoder=$decoderName")

        val codec =
            if (decoderName != null) {
                MediaCodec.createByCodecName(decoderName)
            } else {
                MediaCodec.createDecoderByType(mime)
            }

        val callback =
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                ) {
                    availableInputBuffers.offer(index)
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    handleOutputBuffer(codec, index, info)
                }

                override fun onError(
                    codec: MediaCodec,
                    e: MediaCodec.CodecException,
                ) {
                    diagLog("Codec error: ${e.diagnosticInfo}")
                    Log.e(TAG, "Codec error: ${e.diagnosticInfo}", e)
                    needsKeyframe = true
                    requestKeyframe("codec error", force = true)
                }

                override fun onOutputFormatChanged(
                    codec: MediaCodec,
                    format: MediaFormat,
                ) {
                    diagLog("Output format changed: $format")
                }
            }
        codec.setCallback(callback, decoderHandler)

        configureWithBestSupportedHints(codec, mime, surface, callback)

        codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        needsKeyframe = true
        isRunning = true
        codec.start()
        decoder = codec
        diagLog(
            "Decoder started: ${currentWidth}x$currentHeight @ ${displayRefreshRate}Hz, " +
                "surface=$surface, valid=${surface.isValid}",
        )
    }

    /**
     * Configures [codec] with as many latency hints as it will accept, dropping
     * the least valuable one at a time until `configure` succeeds.
     *
     * This used to be three fixed attempts: all four hints, then none of them,
     * then bare resolution. On the MatePad Pro 12.2 the first attempt fails with
     * OMX error 0x80001001, so the decoder ran with **no** latency hints at all.
     * Measured cause: this OMX decoder rejects `KEY_LOW_LATENCY` itself — that
     * one unsupported key was costing us every other hint too.
     *
     * So each hint is probed on its own first, and only the accepted ones are
     * combined. Dropping from one end of a fixed order is not enough: whichever
     * end `KEY_LOW_LATENCY` sits at, it would either be dropped first (losing a
     * hint that works elsewhere) or last (losing everything behind it, which is
     * exactly the bug).
     *
     * `KEY_MAX_B_FRAMES` is gone entirely: it is an encoder key, and the Mac
     * already disables frame reordering, so it bought nothing and could only be
     * a reason to fail.
     */
    private fun configureWithBestSupportedHints(
        codec: MediaCodec,
        mime: String,
        surface: Surface,
        callback: MediaCodec.Callback,
    ) {
        val hints: List<Pair<String, (MediaFormat) -> Unit>> =
            listOf(
                "lowLatency" to { f -> f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) },
                "priority" to { f -> f.setInteger(MediaFormat.KEY_PRIORITY, 0) },
                // Ask the codec to run flat out rather than pace itself to the
                // stream rate; Short.MAX_VALUE is the documented "as fast as
                // possible" value.
                "operatingRate" to { f -> f.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) },
            )

        // Probe individually, then narrow the accepted set if the combination is
        // refused even though each part was fine.
        val accepted =
            hints.filter {
                tryConfigure(codec, mime, surface, callback, listOf(it), keepConfiguration = false) != null
            }
        for (count in accepted.size downTo 0) {
            val names = tryConfigure(codec, mime, surface, callback, accepted.take(count), keepConfiguration = true)
            if (names != null) {
                diagLog("Configured with latency hints: $names")
                return
            }
        }

        // Even bare resolution was refused — nothing left to try.
        val failure = IllegalStateException("MediaCodec refused every configuration for $mime")
        Log.e(TAG, "All configure attempts failed", failure)
        codec.release()
        decoderThread?.quitSafely()
        decoderThread = null
        decoderHandler = null
        throw failure
    }

    /**
     * Attempts one configuration, returning the applied hint names on success
     * and null on rejection. Always leaves [codec] usable: a failed configure
     * needs a reset, and reset drops the callback with it.
     *
     * @param keepConfiguration false while probing — a configured codec refuses
     *   the next `configure`, so a successful probe has to be undone or every
     *   later probe would look rejected.
     */
    private fun tryConfigure(
        codec: MediaCodec,
        mime: String,
        surface: Surface,
        callback: MediaCodec.Callback,
        hints: List<Pair<String, (MediaFormat) -> Unit>>,
        keepConfiguration: Boolean,
    ): String? {
        val format = MediaFormat.createVideoFormat(mime, currentWidth, currentHeight)
        hints.forEach { (_, apply) -> apply(format) }
        val names = if (hints.isEmpty()) "none" else hints.joinToString("+") { it.first }
        return try {
            codec.configure(format, surface, null, 0)
            if (!keepConfiguration) {
                codec.reset()
                codec.setCallback(callback, decoderHandler)
            }
            names
        } catch (e: Exception) {
            diagLog("Configure rejected [$names]: ${e.message}")
            codec.reset()
            codec.setCallback(callback, decoderHandler)
            null
        }
    }

    /**
     * Find the best decoder for [mime] at the given resolution.
     * Prefers hardware decoders, falls back to software if HW can't handle the resolution.
     * Returns codec name to use with MediaCodec.createByCodecName(), or null for default.
     */
    private fun findBestDecoder(
        width: Int,
        height: Int,
    ): String? {
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val targetRate = displayRefreshRate.toDouble().coerceAtLeast(30.0)
            var hwRateDecoder: String? = null
            var hwSizeDecoder: String? = null
            var swRateDecoder: String? = null
            var swSizeDecoder: String? = null

            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val caps =
                    try {
                        info.getCapabilitiesForType(mime)
                    } catch (_: Exception) {
                        continue
                    }

                val videoCaps = caps.videoCapabilities ?: continue
                val isHardware =
                    !info.name.startsWith("c2.android.") &&
                        !info.name.startsWith("OMX.google.")
                val supported = videoCaps.isSizeSupported(width, height)
                val rateSupported =
                    supported &&
                        try {
                            videoCaps.areSizeAndRateSupported(width, height, targetRate)
                        } catch (_: Exception) {
                            false
                        }

                diagLog(
                    "$mime decoder '${info.name}': " +
                        "width=${videoCaps.supportedWidths}, " +
                        "height=${videoCaps.supportedHeights}, " +
                        "hw=$isHardware, supports ${width}x$height=$supported, " +
                        "supports @${"%.0f".format(targetRate)}fps=$rateSupported",
                )

                if (supported) {
                    if (isHardware && rateSupported && hwRateDecoder == null) {
                        hwRateDecoder = info.name
                    } else if (isHardware && hwSizeDecoder == null) {
                        hwSizeDecoder = info.name
                    } else if (!isHardware && rateSupported && swRateDecoder == null) {
                        swRateDecoder = info.name
                    } else if (!isHardware && swSizeDecoder == null) {
                        swSizeDecoder = info.name
                    }
                }
            }

            // Prefer hardware that advertises the target refresh rate, then any
            // hardware decoder for the size, then software as a last resort.
            val chosen = hwRateDecoder ?: hwSizeDecoder ?: swRateDecoder ?: swSizeDecoder
            if (chosen != null) {
                diagLog(
                    "Selected decoder: $chosen " +
                        "(rateSupported=${chosen == hwRateDecoder || chosen == swRateDecoder})",
                )
            } else {
                diagLog("No decoder supports ${width}x$height — will use default")
            }
            return chosen
        } catch (e: Exception) {
            diagLog("Decoder search failed: ${e.message}")
        }
        return null
    }

    fun decode(
        frameData: ByteArray,
        frameSize: Int = frameData.size,
        frameTimestamp: Long = System.nanoTime(),
        isKeyframe: Boolean = false,
    ) {
        if (!isRunning) {
            diagLog("decode called but isRunning=false")
            onFrameDecoded?.invoke(frameData)
            return
        }

        inputFrameCount++
        if (inputFrameCount == 1L) {
            val header =
                frameData
                    .take(minOf(16, frameSize))
                    .joinToString(" ") { String.format("%02x", it) }
            diagLog(
                "First frame: size=$frameSize, header=[$header], " +
                    "keyframe=$isKeyframe, surface=$surface, valid=${surface.isValid}",
            )
        }
        if (inputFrameCount % 60L == 0L) {
            diagLog(
                "Decode stats: input=$inputFrameCount, output=$outputFrameCount, " +
                    "dropped=$droppedFrames, availBufs=${availableInputBuffers.size}",
            )
        }
        val codec =
            decoder ?: run {
                diagLog("decoder is null in decode()")
                onFrameDecoded?.invoke(frameData)
                return
            }

        if (needsKeyframe && !isKeyframe) {
            dropFrame(
                frameData,
                isKeyframe,
                "waiting for keyframe",
                waitForKeyframe = true,
            )
            return
        }

        // Direct feed: grab an available input buffer and queue immediately.
        // A short wait first, because dropping a compressed frame is expensive:
        // it breaks the P-frame reference chain and forces a keyframe, so a
        // momentarily empty pool must not cost a whole GOP. The wait doubles as
        // backpressure — it paces this thread to the decoder instead of
        // overfeeding and then panicking.
        //
        // Measured on a MatePad Pro 12.2 at 2800x1840 under synthetic full-screen
        // motion: 13 pool-exhaustion drops in 15s at 90Hz and many more at 120Hz
        // before this wait; zero at both after. The cost is decoder latency,
        // which rose from ~38ms at 90Hz to ~54-62ms at 120Hz as the wait absorbs
        // the overfeed — still inside MAX_RENDER_LATENCY_NS, so nothing is
        // discarded downstream.
        val waitStartNs = System.nanoTime()
        val index = availableInputBuffers.poll(INPUT_BUFFER_WAIT_MS, TimeUnit.MILLISECONDS)
        // Split out the pool wait: the total receive-to-decoded figure lumps it
        // together with the codec's own pipeline, and the two have completely
        // different fixes.
        inputWaitSumNs += System.nanoTime() - waitStartNs
        inputWaitSamples++
        if (index == null) {
            // Pool still empty after waiting. Do NOT pause the pipeline — keep
            // feeding so the cursor tracks live. Reference state diverges
            // briefly (cursor trail visible), but a force-keyframe request
            // bypasses every layer's throttle and rebuilds the reference within
            // ~100-200 ms, which feels better than a 1-2 s freeze waiting on
            // the next throttled request to land.
            droppedFrames++
            if (droppedFrames <= 3L || droppedFrames % 60L == 0L) {
                diagLog("Dropping frame (no input buffer, dropped=$droppedFrames)")
            }
            requestKeyframe("no input buffer", force = true)
            onFrameDecoded?.invoke(frameData)
            return
        }

        queueFrame(codec, index, frameData, frameSize, frameTimestamp, isKeyframe)
    }

    private fun queueFrame(
        codec: MediaCodec,
        index: Int,
        frameData: ByteArray,
        frameSize: Int,
        frameTimestamp: Long,
        isKeyframe: Boolean,
    ) {
        try {
            val inputBuffer =
                codec.getInputBuffer(index)
                    ?: throw IllegalStateException("Input buffer $index is null")
            inputBuffer.clear()
            inputBuffer.put(frameData, 0, frameSize)
            codec.queueInputBuffer(index, 0, frameSize, frameTimestamp / 1000, 0)
            if (isKeyframe && keyframePtsUs.size < KEYFRAME_PTS_LIMIT) {
                keyframePtsUs.add(frameTimestamp / 1000)
            }
            queuedInputCount++
            if (queuedInputCount == STALL_DETECT_INPUT_FRAMES && outputFrameCount == 0L && !stallReported) {
                stallReported = true
                diagLog("Decoder stalled: $queuedInputCount frames queued, none out")
                onDecoderStalled?.invoke()
            }
            if (isKeyframe) {
                needsKeyframe = false
            }
        } catch (e: Exception) {
            needsKeyframe = true
            requestKeyframe("queue input failed")
            Log.e(TAG, "decode direct feed error", e)
        } finally {
            onFrameDecoded?.invoke(frameData)
        }
    }

    private fun dropFrame(
        frameData: ByteArray,
        isKeyframe: Boolean,
        reason: String,
        waitForKeyframe: Boolean,
        requestRefresh: Boolean = waitForKeyframe,
    ) {
        droppedFrames++
        if (droppedFrames <= 3L || droppedFrames % 60L == 0L) {
            diagLog("Dropping frame ($reason, keyframe=$isKeyframe, dropped=$droppedFrames)")
        }
        if (waitForKeyframe) {
            needsKeyframe = true
        }
        if (requestRefresh) {
            requestKeyframe(reason)
        }
        onFrameDecoded?.invoke(frameData)
    }

    private fun requestKeyframe(
        reason: String,
        force: Boolean = false,
    ) {
        val now = System.nanoTime()
        val interval =
            if (force) FORCE_KEYFRAME_REQUEST_INTERVAL_NS else KEYFRAME_REQUEST_INTERVAL_NS
        if (now - lastKeyframeRequestNs < interval) {
            return
        }
        lastKeyframeRequestNs = now
        diagLog("Requesting keyframe: reason=$reason, force=$force")
        onKeyframeRequired?.invoke(force, reason)
    }

    private fun handleOutputBuffer(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        try {
            outputFrameCount++
            if (outputFrameCount == 1L) {
                diagLog("First output frame! size=${info.size}, flags=${info.flags}")
            }

            // Decoder latency: time from queueInputBuffer (where we encoded
            // System.nanoTime()/1000 as PTS) to now. Captures how long the
            // frame spent inside the codec's input/reorder/output queues.
            val nowNs = System.nanoTime()
            val latencyNs = nowNs - info.presentationTimeUs * 1000L
            val hasValidLatency = latencyNs in 0..MAX_REASONABLE_LATENCY_NS
            if (hasValidLatency) {
                latencySumNs += latencyNs
                latencySamples++
                if (latencyNs > latencyMaxNs) latencyMaxNs = latencyNs
                // Keyframes are an order of magnitude larger than P-frames, so
                // they are the obvious suspect for the max being ~1.6x the
                // average. Tracked separately to confirm that before changing
                // the encoder's keyframe interval on a hunch. Matched by PTS
                // recorded at input, because this decoder does not set
                // BUFFER_FLAG_KEY_FRAME on output buffers — the flag is an
                // input-side concept here and reads as zero for every frame.
                if (keyframePtsUs.remove(info.presentationTimeUs)) {
                    keyframeLatencySumNs += latencyNs
                    keyframeLatencySamples++
                }
            }

            if (outputFrameCount % 60L == 0L) {
                val avgMs = if (latencySamples > 0) latencySumNs / latencySamples / 1_000_000.0 else 0.0
                val maxMs = latencyMaxNs / 1_000_000.0
                val inBufs = availableInputBuffers.size
                // The total is receive-to-decoded. Subtracting the pool wait
                // leaves the codec's own pipeline, which is the part no amount of
                // feeding discipline can shorten.
                val waitMs = if (inputWaitSamples > 0) inputWaitSumNs / inputWaitSamples / 1_000_000.0 else 0.0
                val keyframeAvgMs =
                    if (keyframeLatencySamples > 0) {
                        keyframeLatencySumNs / keyframeLatencySamples / 1_000_000.0
                    } else {
                        0.0
                    }
                diagLog(
                    "Output #$outputFrameCount: decoder latency avg=${"%.1f".format(avgMs)}ms " +
                        "max=${"%.1f".format(maxMs)}ms over $latencySamples samples " +
                        "(poolWait=${"%.1f".format(waitMs)}ms, codec=${"%.1f".format(avgMs - waitMs)}ms, " +
                        "keyframes=${"%.1f".format(keyframeAvgMs)}ms x$keyframeLatencySamples), " +
                        "input bufs avail=$inBufs, dropped=$droppedFrames",
                )
                inputWaitSumNs = 0
                inputWaitSamples = 0
                keyframeLatencySumNs = 0
                keyframeLatencySamples = 0
                latencySumNs = 0
                latencySamples = 0
                latencyMaxNs = 0
            }

            val shouldRender =
                outputFrameCount == 1L ||
                    !hasValidLatency ||
                    latencyNs <= MAX_RENDER_LATENCY_NS

            if (!shouldRender) {
                droppedFrames++
                staleOutputDrops++
                if (staleOutputDrops <= 3L || staleOutputDrops % 60L == 0L) {
                    diagLog(
                        "Dropping stale output frame: latency=${"%.1f".format(latencyNs / 1_000_000.0)}ms, " +
                            "staleDrops=$staleOutputDrops",
                    )
                }
                codec.releaseOutputBuffer(index, false)
                updateStats()
                return
            }

            codec.releaseOutputBuffer(index, true)
            trackFrameTiming(System.nanoTime())
            updateStats()
        } catch (e: Exception) {
            Log.e(TAG, "releaseOutputBuffer failed", e)
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: Exception) {
            }
        }
    }

    private fun trackFrameTiming(timestamp: Long) {
        frameTimes.addLast(timestamp)
        if (frameTimes.size > 120) frameTimes.removeFirst()

        if (frameTimes.size >= 60 && frameCount % 60L == 0L) {
            val deltas = frameTimes.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
            if (deltas.isNotEmpty()) {
                val avgDelta = deltas.average()
                val variance = deltas.map { (it - avgDelta) * (it - avgDelta) }.average()
                val stdDev = kotlin.math.sqrt(variance)
                onFrameStats?.invoke(1000.0 / avgDelta, stdDev)
            }
        }
        onFrameRendered?.invoke(timestamp)
    }

    private fun updateStats() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatsTime
        if (elapsed >= 1000) {
            frameCount = 0
            droppedFrames = 0
            staleOutputDrops = 0
            lastStatsTime = now
        }
    }

    fun release() {
        isRunning = false
        try {
            availableInputBuffers.clear()
            decoder?.stop()
            decoder?.release()
            decoder = null
            decoderThread?.quitSafely()
            decoderThread = null
            decoderHandler = null
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val STALL_DETECT_INPUT_FRAMES = 120L
        private const val KEYFRAME_REQUEST_INTERVAL_NS = 1_000_000_000L
        private const val FORCE_KEYFRAME_REQUEST_INTERVAL_NS = 200_000_000L
        /**
         * How long to wait for a MediaCodec input buffer before giving up and
         * forcing a keyframe. Two frame intervals at 90Hz (~22ms), which covers
         * the transient exhaustion measured on the MatePad Pro 12.2.
         *
         * ponytail: this blocks the network receive thread, which also carries
         * audio — 20ms is inside the AudioPlayer's 40ms buffer, so a stall is
         * absorbed rather than audible. Raising it much past that would trade
         * video freezes for audio dropouts.
         */
        private const val INPUT_BUFFER_WAIT_MS = 20L
        /** Dropped frames never produce output, so unmatched PTS need a ceiling. */
        private const val KEYFRAME_PTS_LIMIT = 64

        private const val MAX_RENDER_LATENCY_NS = 100_000_000L
        private const val MAX_REASONABLE_LATENCY_NS = 2_000_000_000L
    }
}
