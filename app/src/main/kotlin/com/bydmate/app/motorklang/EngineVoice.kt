package com.bydmate.app.motorklang

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Small, fast noise source; java.util.Random is too slow per sample on a head unit. */
internal class FastRandom(seed: Long = 0x2545F4914F6CDD1DL) {
    private var s = if (seed == 0L) 1L else seed
    /** Uniform in [0, 1). */
    fun next(): Double {
        s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
        return ((s ushr 11).toDouble()) * (1.0 / (1L shl 53).toDouble())
    }
    /** Uniform in [-1, 1). */
    fun bi(): Double = next() * 2 - 1
}

/**
 * WATTSON fork: the physical engine model of the Motorklang prototype, sample by sample.
 *
 * Exhaust blowdown pulse per cylinder → header runner (delay line) → exhaust pipe per bank with
 * reflection → muffler → low-pass + DC block. Ported 1:1 from the prototype's AudioWorklet.
 */
internal class EngineVoice(private val sr: Double, profile: EngineProfile, private val rnd: FastRandom = FastRandom()) {

    // Targets set once per audio block by the player.
    @Volatile var targetRpm = profile.idle.toDouble()
    @Volatile var targetLoad = 0.0
    @Volatile var targetCoast = 0.0
    @Volatile var targetCutoff = 600.0
    @Volatile var pendingPops = 0

    private var rpm = profile.idle.toDouble()
    private var load = 0.0
    private var coast = 0.0
    private var fc = 600.0
    private var theta = 0.0
    private var hunt = 0.0

    // Config (cfgOf in the prototype).
    private val n = profile.cylinders
    private val amps = DoubleArray(n)
    private val fire = DoubleArray(n)
    private val bankOf = IntArray(n)
    private val dual = profile.banks != null
    private val evo = 135.0
    private val dur = 200 + 60 / profile.sharp
    private val sk = 2 + 2.6 * profile.sharp
    private val noiseAmt = profile.noise * .8
    private val lope = profile.lope
    private val burble = profile.burble
    private val clat = if (profile.diesel) profile.clatter * 1.4 else 0.0
    private val intakeAmt = if (profile.isSupercharged) .25 else .5
    private val drive = profile.drive
    private val tr = .9 + .5 * profile.sharp
    private val gain = .75
    private val fb = min(.8, profile.feedback + .05)

    // Per-cylinder state.
    private val a = DoubleArray(n)
    private val popA = DoubleArray(n)
    private val knock = DoubleArray(n)
    private val tk = IntArray(n) { 1_000_000_000 }
    private val dA = DoubleArray(n)
    private val ir = 1 / (0.00015 * sr)
    private val id = 1 / (0.0016 * sr)
    private val inz = 1 / (0.0009 * sr)
    private val tlen = (0.012 * sr).roundToInt()
    private val rb: Array<DoubleArray>
    private val rw = IntArray(n)
    private val rlp = DoubleArray(n)

    // Pipes and mufflers per bank.
    private val pl: Int
    private val ml: Int
    private val pb: Array<DoubleArray>
    private val pw = IntArray(2)
    private val plp = DoubleArray(2)
    private val mb: Array<DoubleArray>
    private val mw = IntArray(2)
    private val t1 = DoubleArray(2)
    private val t2 = DoubleArray(2)
    private val hx = DoubleArray(2)
    private val hy = DoubleArray(2)
    private var ix1 = 0.0
    private var ix2 = 0.0
    private val bs = DoubleArray(2)
    private val ob = DoubleArray(2)

    init {
        for (i in 0 until n) {
            var amp = profile.amps?.get(i) ?: 1.0
            val banks = profile.banks
            if (banks != null && profile.cross != 0.0) {
                val prev = (i - 1 + n) % n
                amp *= if (banks[i] == banks[prev]) 1 + profile.cross * .35 else 1 - profile.cross * .1
            }
            amps[i] = amp
            fire[i] = ((i + (profile.offsets?.get(i) ?: 0.0)) * 720 / n + 720) % 720
            bankOf[i] = banks?.get(i) ?: 0
        }
        rb = Array(n) { i ->
            val runner = profile.runner * (1 + ((i * 37) % 11 - 5) * .012)
            DoubleArray(max(2, (runner / 343 * sr).roundToInt()))
        }
        pl = max(16, (2 * profile.pipe / 343 * sr).roundToInt())
        ml = max(8, (2 * (.25 + profile.pipe * .15) / 343 * sr).roundToInt())
        pb = arrayOf(DoubleArray(pl), DoubleArray(pl))
        mb = arrayOf(DoubleArray(ml), DoubleArray(ml))
    }

    /** Renders [frames] stereo frames into [left]/[right] starting at index 0. */
    // A 1:1 port of the prototype's per-sample loop; splitting it would cost calls in the hot path.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (s in 0 until frames) {
            rpm += (targetRpm - rpm) * 0.0015
            load += (targetLoad - load) * 0.003
            coast += (targetCoast - coast) * 0.002
            fc += (targetCutoff - fc) * 0.002
            hunt += (rnd.next() - 0.5) * 0.02 - hunt * 0.0004
            val rpmE = max(200.0, rpm * (1 + hunt * 0.012 * (1 - 0.7 * load)))
            val prev = theta
            var th = prev + rpmE * 6 / sr
            if (th >= 720) th -= 720
            theta = th
            bs[0] = 0.0; bs[1] = 0.0
            var intake = 0.0
            var knk = 0.0
            for (i in 0 until n) {
                val f = fire[i]
                var rel = th - f; if (rel < 0) rel += 720
                var prel = prev - f; if (prel < 0) prel += 720
                if (rel < prel) { // this cylinder fired during the sample
                    val varA = lope * (1 - load) + 0.07
                    val gs = (rnd.next() + rnd.next() + rnd.next() - 1.5) * 1.4
                    dA[i] = dA[i] * 0.96 + (rnd.next() - 0.5) * 0.07
                    val rr = rnd.next()
                    val coastLvl = if (rr < burble * 0.22) 0.06 else if (rr > 1 - burble * 0.18) 0.85 else 0.36 + rnd.next() * 0.16
                    val base = (0.3 + 0.7 * load) * (1 - coast) + coast * coastLvl
                    a[i] = amps[i] * base * max(0.0, 1 + gs * varA + dA[i])
                    if (pendingPops > 0 && coast > 0.5 && rnd.next() < 0.6) {
                        popA[i] = 1.2 + rnd.next() * 1.8
                        pendingPops--
                    }
                    knock[i] = clat * (1 - 0.4 * load)
                    tk[i] = -(evo / (rpmE * 6 / sr)).roundToInt()
                }
                var tv = 0.0
                val t = tk[i]++
                if (t in 0 until tlen) {
                    val amp = a[i] * tr
                    tv = amp * (1 - exp(-t * ir)) * exp(-t * id) + amp * 0.5 * rnd.bi() * exp(-t * inz)
                }
                var v = 0.0
                val ex = rel - evo
                if (ex >= 0 && ex < dur) {
                    val x = ex / dur
                    val sh = if (x < 0.05) x / 0.05 else exp(-(x - 0.05) * sk)
                    v = a[i] * sh * (1 + noiseAmt * rnd.bi())
                    if (popA[i] > 0) {
                        if (x < 0.12) v += popA[i] * rnd.bi() * (1 - x / 0.12) else popA[i] = 0.0
                    }
                }
                val it = rel - 370
                if (it >= 0 && it < 200) intake += a[i] * sin(PI * it / 200) * rnd.bi()
                if (knock[i] > 0.001) { knk += knock[i] * rnd.bi(); knock[i] *= 0.93 }
                v += tv
                val buf = rb[i]
                val w = rw[i]
                val dly = buf[w]
                buf[w] = v
                rw[i] = (w + 1) % buf.size
                rlp[i] += (dly - rlp[i]) * 0.6
                if (dual && bankOf[i] == 1) bs[1] += rlp[i] else bs[0] += rlp[i]
            }
            val nb = if (dual) 2 else 1
            val fcA = 1 - exp(-2 * PI * min(fc, sr * 0.45) / sr)
            val dr = 1 + drive * load * 0.6
            for (b in 0 until nb) {
                val p = pb[b]
                val w = pw[b]
                val d = p[w]
                plp[b] += (d - plp[b]) * 0.45
                var y = bs[b] - fb * plp[b]
                p[w] = y
                pw[b] = (w + 1) % pl
                y = tanh(y * dr * 0.8) / 0.8
                val m = mb[b]
                val mi = mw[b]
                val mm = y + 0.35 * m[mi]
                m[mi] = mm * 0.9
                mw[b] = (mi + 1) % ml
                t1[b] += (mm - t1[b]) * fcA
                t2[b] += (t1[b] - t2[b]) * fcA
                val h = 0.991 * (hy[b] + t2[b] - hx[b]); hx[b] = t2[b]; hy[b] = h
                ob[b] = h
            }
            ix1 += (intake - ix1) * 0.25
            ix2 += (ix1 - ix2) * 0.05
            val inOut = (ix1 - ix2) * intakeAmt * (0.2 + load)
            val k = knk * 0.5
            val l: Double
            val r: Double
            if (dual) { l = ob[0] * 0.72 + ob[1] * 0.28; r = ob[1] * 0.72 + ob[0] * 0.28 } else { l = ob[0]; r = ob[0] }
            left[s] = tanh((l + inOut + k) * gain).toFloat()
            right[s] = tanh((r + inOut + k) * gain).toFloat()
        }
    }
}
