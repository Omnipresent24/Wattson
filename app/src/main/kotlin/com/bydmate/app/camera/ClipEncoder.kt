package com.bydmate.app.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * WATTSON fork: H.264 encoder for one camera channel with a rolling pre-event buffer.
 *
 * The camera draws straight into [inputSurface]. Encoded frames are kept in memory for the last
 * [bufferUs]; [startSaving] writes that buffer (from its oldest keyframe) to an MP4 and keeps
 * appending live frames until [stopSaving]. Nothing touches the disk until a save is asked for,
 * so the buffer can run for a whole drive without filling the card.
 */
class ClipEncoder(
    private val label: String,
    private val bufferUs: Long = DEFAULT_BUFFER_US,
) {
    private class Sample(val data: ByteArray, val ptsUs: Long, val keyFrame: Boolean)

    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    private val lock = Object()
    private val ring = ArrayDeque<Sample>()
    private var outputFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var firstWrittenPtsUs = -1L
    private var lastWrittenPtsUs = -1L

    var inputSurface: Surface? = null; private set
    var width = 0; private set
    var height = 0; private set

    /** File currently being written, or null while only buffering. */
    var savingTo: File? = null; private set

    /** Frames that came out of the encoder since [start]; tells a dead camera from a live one. */
    @Volatile var framesEncoded = 0L; private set

    /**
     * Configures the encoder at [w]×[h] (rounded down to a multiple of 16, which every hardware
     * encoder accepts) and returns its input surface, or null when the device refused the size.
     */
    fun start(w: Int, h: Int, bitRate: Int = DEFAULT_BIT_RATE, fps: Int = DEFAULT_FPS): Surface? {
        stop()
        val ew = w / 16 * 16
        val eh = h / 16 * 16
        if (ew <= 0 || eh <= 0) return null
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ew, eh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                // One keyframe a second: a saved clip can start at most one second before the
                // oldest buffered moment.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            inputSurface = surface
            width = ew
            height = eh
            framesEncoded = 0
            running = true
            drainThread = Thread({ drainLoop(c) }, "ClipEncoder-$label").apply { start() }
            Log.i(TAG, "$label: encoder ${ew}x$eh started")
            surface
        } catch (e: Exception) {
            Log.w(TAG, "$label: encoder ${ew}x$eh refused: $e")
            stop()
            null
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                c.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                break
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    outputFormat = c.outputFormat
                }
                index >= 0 -> {
                    val buffer: ByteBuffer? = c.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        onSample(Sample(bytes, info.presentationTimeUs, key))
                    }
                    c.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun onSample(sample: Sample) = synchronized(lock) {
        framesEncoded++
        if (muxer != null) {
            write(sample)
        } else {
            ring.addLast(sample)
            trimRing(sample.ptsUs)
        }
    }

    /** Drops whole seconds (keyframe groups) older than [bufferUs] behind [nowUs]. */
    private fun trimRing(nowUs: Long) {
        while (ring.size > 1) {
            // The oldest keyframe after the head: everything before it can go if it is still
            // older than the window, so the buffer always starts on a keyframe.
            val head = ring.peekFirst() ?: return
            if (nowUs - head.ptsUs <= bufferUs) return
            ring.pollFirst()
            while (ring.peekFirst()?.keyFrame == false) ring.pollFirst()
        }
    }

    /**
     * Starts writing to [file]: the buffered past first, then every new frame. Returns false
     * when the encoder has not produced a format yet or the file could not be opened.
     */
    fun startSaving(file: File): Boolean = synchronized(lock) {
        if (muxer != null) return true
        val format = outputFormat ?: return false
        return try {
            file.parentFile?.mkdirs()
            val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            track = m.addTrack(format)
            m.start()
            muxer = m
            savingTo = file
            firstWrittenPtsUs = -1L
            lastWrittenPtsUs = -1L
            // Skip to the first keyframe: a clip that starts on a delta frame shows garbage.
            while (ring.peekFirst()?.keyFrame == false) ring.pollFirst()
            while (true) write(ring.pollFirst() ?: break)
            Log.i(TAG, "$label: saving to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "$label: cannot save to $file: $e")
            muxer = null
            savingTo = null
            false
        }
    }

    private fun write(sample: Sample) {
        val m = muxer ?: return
        if (firstWrittenPtsUs < 0) {
            if (!sample.keyFrame) return
            firstWrittenPtsUs = sample.ptsUs
        }
        val pts = sample.ptsUs - firstWrittenPtsUs
        if (pts <= lastWrittenPtsUs) return // the muxer refuses non-increasing timestamps
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.data.size, pts, if (sample.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        m.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
        lastWrittenPtsUs = pts
    }

    /** Length of what has been written so far, in microseconds. */
    val savedDurationUs: Long get() = synchronized(lock) { maxOf(0L, lastWrittenPtsUs) }

    /** Finishes the current file; buffering goes on. Returns the file, or null if none was open. */
    fun stopSaving(): File? = synchronized(lock) {
        val m = muxer ?: return null
        val file = savingTo
        runCatching { m.stop() }.onFailure { Log.w(TAG, "$label: muxer stop: $it") }
        runCatching { m.release() }
        muxer = null
        savingTo = null
        track = -1
        Log.i(TAG, "$label: saved ${file?.name}")
        file
    }

    /** Stops everything; an open file is finished first. */
    fun stop() {
        stopSaving()
        running = false
        drainThread?.join(STOP_JOIN_MS)
        drainThread = null
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
        synchronized(lock) {
            ring.clear()
            outputFormat = null
        }
    }

    companion object {
        private const val TAG = "ClipEncoder"
        const val DEFAULT_BUFFER_US = 30_000_000L
        const val DEFAULT_BIT_RATE = 4_000_000
        const val DEFAULT_FPS = 25
        private const val DRAIN_TIMEOUT_US = 50_000L
        private const val STOP_JOIN_MS = 1_000L
    }
}
