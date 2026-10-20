package com.bydmate.app.motorklang

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * WATTSON fork: plays Motorklang through the head unit's speakers.
 *
 * One audio thread renders blocks of [BLOCK] frames: simulation step → engine model → mixer →
 * AudioTrack. It does not take audio focus, so music keeps playing underneath; the system
 * ducks it for navigation prompts and calls like any other media stream.
 */
internal class MotorklangPlayer(
    profile: EngineProfile,
    ownVmaxKmh: Int,
    volume: Float,
) {
    private val sr: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 22050..96000 } ?: 48000
    val sim = EngineSim(profile, ownVmaxKmh)
    private val voice = EngineVoice(sr.toDouble(), profile)
    private val mixer = MotorklangMixer(sr.toDouble(), profile).apply { this.volume = volume.toDouble() }
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var lastReadingS: Double? = null

    /** Fade in/out so start and stop never click. */
    @Volatile private var fadeTarget = 1.0
    private var fade = 0.0

    var volume: Float
        get() = mixer.volume.toFloat()
        set(v) { mixer.volume = v.toDouble() }

    fun onCarReading(speedKmh: Int?, powerKw: Double?) = synchronized(sim) {
        val now = SystemClock.elapsedRealtime() / 1000.0
        sim.onCarReading((speedKmh ?: 0).toDouble(), powerKw, now, lastReadingS)
        lastReadingS = now
    }

    fun startDemo() = synchronized(sim) { sim.startDemo() }

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, BLOCK * 8 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioTrack refused: $e")
            return
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "AudioTrack refused: $e")
            return
        }
        track = t
        running = true
        fadeTarget = 1.0
        t.play()
        thread = Thread({ loop(t) }, "Motorklang").apply { start() }
        Log.i(TAG, "started at $sr Hz, profile ${sim.profile.id}")
    }

    /** Fades out and stops; returns at once, the thread finishes on its own. */
    fun stop() {
        fadeTarget = 0.0
    }

    val isRunning get() = running

    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val l = FloatArray(BLOCK)
        val r = FloatArray(BLOCK)
        val out = FloatArray(BLOCK * 2)
        val dt = BLOCK.toDouble() / sr
        val fadeStep = dt / FADE_S
        try {
            while (running) {
                synchronized(sim) { sim.step(dt) }
                voice.targetRpm = sim.rpm
                voice.targetLoad = sim.load
                voice.targetCoast = sim.coast
                voice.targetCutoff = sim.profile.muffler * (1.3 + sim.load * 1.8 + sim.coast * .5) + sim.rpm * .3 + 450
                if (sim.pop) voice.pendingPops++
                mixer.update(sim, BLOCK)
                voice.render(l, r, BLOCK)
                mixer.mix(l, r, BLOCK)
                for (i in 0 until BLOCK) {
                    fade += if (fade < fadeTarget) fadeStep / BLOCK else if (fade > fadeTarget) -fadeStep / BLOCK else 0.0
                    fade = fade.coerceIn(0.0, 1.0)
                    out[2 * i] = (l[i] * fade).toFloat()
                    out[2 * i + 1] = (r[i] * fade).toFloat()
                }
                t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (fadeTarget == 0.0 && fade <= 0.0) running = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio loop ended: $e")
        } finally {
            running = false
            runCatching { t.stop() }
            runCatching { t.release() }
            Log.i(TAG, "stopped")
        }
    }

    private companion object {
        const val TAG = "Motorklang"
        const val BLOCK = 256
        const val FADE_S = .4
    }
}
