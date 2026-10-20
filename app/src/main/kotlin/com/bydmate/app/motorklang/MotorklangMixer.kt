package com.bydmate.app.motorklang

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** RBJ biquad, direct form I. */
internal class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun peaking(sr: Double, f: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * f.coerceIn(10.0, sr * .45) / sr
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0; b1 = -2 * cos(w) / a0; b2 = (1 - alpha * a) / a0
        a1 = -2 * cos(w) / a0; a2 = (1 - alpha / a) / a0
    }

    fun bandpass(sr: Double, f: Double, q: Double) {
        val w = 2 * PI * f.coerceIn(10.0, sr * .45) / sr
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        b0 = alpha / a0; b1 = 0.0; b2 = -alpha / a0; a1 = -2 * cos(w) / a0; a2 = (1 - alpha) / a0
    }

    fun highpass(sr: Double, f: Double, q: Double = .707) {
        val w = 2 * PI * f.coerceIn(10.0, sr * .45) / sr
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        val c = cos(w)
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0; a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/** Short damped comb reverb standing in for the prototype's 0.22 s cabin impulse response. */
internal class CabinReverb(sr: Double) {
    private val lines = doubleArrayOf(.0113, .0137, .0171, .0193).map { DoubleArray((it * sr).toInt().coerceAtLeast(8)) }
    private val idx = IntArray(lines.size)
    private val lp = DoubleArray(lines.size)
    // Amplitude falls by 1/e every 45 ms, like the prototype's impulse response.
    private val fb = lines.map { exp(-it.size / sr / .045) }

    fun process(x: Double): Double {
        var out = 0.0
        for (i in lines.indices) {
            val buf = lines[i]
            val y = buf[idx[i]]
            lp[i] += (y - lp[i]) * .25
            buf[idx[i]] = x + lp[i] * fb[i]
            idx[i] = (idx[i] + 1) % buf.size
            out += y
        }
        return out / lines.size
    }
}

/**
 * The prototype's Web Audio graph after the engine model: formant EQ, cabin reverb, turbo
 * whistle and whoosh, supercharger whine, blow-off valve, compressor and master volume.
 */
internal class MotorklangMixer(private val sr: Double, private val profile: EngineProfile, private val rnd: FastRandom = FastRandom(11)) {
    private val fmL = List(3) { Biquad() }
    private val fmR = List(3) { Biquad() }
    private val reverbL = CabinReverb(sr)
    private val reverbR = CabinReverb(sr * 1.003)
    private val whoosh = Biquad()
    private val whine = Biquad()
    private val bov = Biquad().apply { highpass(sr, 1600.0) }

    private var engGain = .75
    private var tPhase = 0.0
    private var tFreq = 2200.0
    private var tGain = 0.0
    private var whFreq = 1200.0
    private var whGain = 0.0
    private var sPhase = 0.0
    private var sFreq = 100.0
    private var sGain = 0.0
    private var bovT = -1.0
    private var env = 0.0

    /** 0..1 from the volume setting. */
    @Volatile var volume = .75

    init {
        profile.fm.forEachIndexed { i, (f, g, q) ->
            fmL[i].peaking(sr, f, g * .6, q); fmR[i].peaking(sr, f, g * .6, q)
        }
    }

    /** Once per block: the prototype's audioFrame(). */
    fun update(sim: EngineSim, frames: Int) {
        val r = sim.rpm / profile.redline
        val k = 1 - exp(-frames / sr / .03)
        engGain += (.75 + .35 * r - engGain) * k
        val k5 = 1 - exp(-frames / sr / .05)
        tFreq += (2200 + sim.boost * 6500 - tFreq) * k5
        tGain += (sim.boost * .02 * profile.turbo - tGain) * k5
        whFreq += (1200 + sim.boost * 2400 - whFreq) * k5
        whGain += (sim.boost * .07 * profile.turbo - whGain) * k5
        val k2 = 1 - exp(-frames / sr / .02)
        sFreq += (sim.rpm * .17 - sFreq) * k2
        val sTarget = if (profile.isSupercharged) profile.supercharger * (.006 + sim.load * .03) * (.25 + r) else 0.0
        sGain += (sTarget - sGain) * (1 - exp(-frames / sr / .04))
        whoosh.bandpass(sr, whFreq, .8)
        whine.bandpass(sr, sFreq, 5.0)
        if (sim.blowOff) bovT = 0.0
    }

    fun mix(left: FloatArray, right: FloatArray, frames: Int) {
        val master = volume * 2.4
        val att = 1 - exp(-1 / (sr * .004))
        val rel = 1 - exp(-1 / (sr * .15))
        for (s in 0 until frames) {
            var l = left[s] * engGain
            var r = right[s] * engGain
            for (i in 0 until 3) { l = fmL[i].process(l); r = fmR[i].process(r) }
            // Extras go into the cabin (mono).
            tPhase += 2 * PI * tFreq / sr; if (tPhase > 2 * PI) tPhase -= 2 * PI
            var extra = sin(tPhase) * tGain
            val noise = rnd.bi()
            extra += whoosh.process(noise) * whGain
            sPhase += sFreq / sr; if (sPhase >= 1) sPhase -= 1
            extra += whine.process(2 * sPhase - 1) * sGain
            if (bovT >= 0) {
                val t = bovT
                val g = if (t < .012) .2 * minOf(1.2, profile.turbo) * t / .012 else .2 * minOf(1.2, profile.turbo) * exp(-(t - .012) / .07)
                extra += bov.process(rnd.bi()) * g
                bovT += 1 / sr
                if (bovT > .45) bovT = -1.0
            }
            l += extra; r += extra
            // Cabin: dry .85 + wet .3.
            l = l * .85 + reverbL.process(l) * .3
            r = r * .85 + reverbR.process(r) * .3
            // Compressor: threshold -16 dB, ratio 5, attack 4 ms, release 150 ms, with makeup.
            val peak = max(abs(l), abs(r))
            env += (peak - env) * (if (peak > env) att else rel)
            val levelDb = 20 * log10(env + 1e-9)
            val overDb = levelDb - THRESHOLD_DB
            val grDb = if (overDb > 0) overDb * (1 - 1 / RATIO) else 0.0
            val g = 10.0.pow((MAKEUP_DB - grDb) / 20) * master
            // Final soft limit: the car's amplifier must never see more than full scale.
            left[s] = tanh(l * g).toFloat()
            right[s] = tanh(r * g).toFloat()
        }
    }

    private companion object {
        const val THRESHOLD_DB = -16.0
        const val RATIO = 5.0
        const val MAKEUP_DB = 6.0
        @Suppress("unused") private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)
    }
}
