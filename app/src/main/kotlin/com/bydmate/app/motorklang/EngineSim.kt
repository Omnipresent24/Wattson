package com.bydmate.app.motorklang

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WATTSON fork: the Motorklang drive simulation (gearbox, rpm, load, turbo), ported from the
 * prototype's GPS mode. Instead of GPS it follows the car's own speed; between two readings it
 * extrapolates with the last acceleration for up to 1.5 s, like the prototype does between fixes.
 *
 * The sound car is scaled to the real one: [ownVmaxKmh] of the real car equals the profile's
 * vmax, so the gears are spread over the same share of the speed range.
 */
internal class EngineSim(val profile: EngineProfile, private val ownVmaxKmh: Int, private val rnd: FastRandom = FastRandom(7)) {

    /** Outputs read by the player after each [step]. */
    var rpm = profile.idle * 1.8; private set
    var load = 0.0; private set
    var coast = 0.0; private set
    var boost = 0.0; private set
    var gear = 0; private set
    /** True for one step when an overrun pop or a turbo blow-off should sound. */
    var pop = false; private set
    var blowOff = false; private set

    private var v = 0.0
    private var shiftT = 0.0
    private var shiftDir = 0
    private var lastLoad = 0.0
    private var jit = 0.0

    // Car input.
    private var carSpeed = 0.0      // m/s
    private var carAcc = 0.0        // m/s²
    private var carAge = 99.0       // s since the last reading
    private var carPowerKw: Double? = null

    // Rev demo ("Hörprobe"): pedal profile over 6.6 s, car standing.
    private var demoT = -1.0
    private var throttle = 0.0
    val demoRunning get() = demoT >= 0

    private val factor get() = profile.vmax.toDouble() / ownVmaxKmh.coerceAtLeast(60)
    private fun ratio(g: Int) = profile.gears[g] * profile.finalDrive
    private fun rpmAt(speed: Double, g: Int) = speed / WHEEL * 60 * ratio(g)

    /** A new reading from the car. [powerKw] > 0 drives, < 0 is regeneration. */
    fun onCarReading(speedKmh: Double, powerKw: Double?, nowS: Double, lastNowS: Double?) {
        val speed = max(0.0, speedKmh / 3.6)
        if (lastNowS != null && nowS > lastNowS && nowS - lastNowS < 5) {
            val acc = (speed - carSpeed) / (nowS - lastNowS)
            carAcc = acc.coerceIn(-10.0, 10.0)
        } else carAcc = 0.0
        carSpeed = speed
        carPowerKw = powerKw
        carAge = 0.0
    }

    fun startDemo() { demoT = 0.0; throttle = 0.0; v = 0.0; gear = 0 }

    // 1:1 port of the prototype's step(); kept in one piece so it can be compared line by line.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun step(dt: Double) {
        val lastGear = profile.gears.size - 1
        if (shiftT > 0) shiftT -= dt
        val shifting = shiftT > 0
        pop = false; blowOff = false
        if (demoT >= 0) {
            demoT += dt
            val th = demoThrottle(demoT)
            throttle += (th - throttle) * min(1.0, dt * 12)
            v = max(0.0, v - 10 * dt); gear = 0; load = throttle
            val tgt = profile.idle + throttle * (profile.redline * .93 - profile.idle)
            rpm += (tgt - rpm) * min(1.0, dt * (if (tgt > rpm) 3.4 * max(.6, profile.accel) else 2.0))
            if (demoT > 6.6) demoT = -1.0
        } else {
            carAge += dt
            val target = if (carAge < 6) max(0.0, carSpeed + carAcc * min(carAge, 1.5)) else 0.0
            val vm = profile.vmax / 3.6
            val simTarget = min(vm, target * factor)
            val a = ((simTarget - v) * 2.2).coerceIn(-9 * factor, 9 * factor)
            v = (v + a * dt).coerceIn(0.0, vm)
            val cruise = .1 + .9 * min(1.0, v / vm).pow(2.4)
            var l = if (a > -.4 * factor) (cruise + a / (3.2 * profile.accel)).coerceIn(0.0, 1.0) else 0.0
            // The car's own power, when known: regeneration means the foot is off the pedal,
            // strong power means a hard push even before the speed has picked up.
            carPowerKw?.let { kw ->
                if (kw < -1) l = min(l, .05) else if (kw > 0) l = max(l * .6, min(1.0, kw / FULL_LOAD_KW))
            }
            if (v < .3 && target < .3) l = 0.0
            if (shifting && shiftDir > 0) l = 0.0
            load = l
            if (shifting && shiftDir < 0) load = max(load, if (shiftT > .08) .5 else 0.0)
            if (!shifting) {
                val now = rpmAt(v, gear)
                val rolling = load < .08
                val upAt = profile.shiftLow + load * (profile.redline - 250 - profile.shiftLow)
                val downAt = if (rolling) max(1250.0, profile.idle + 550.0) else min(1300 + load * 1700, upAt * .55)
                if (!rolling && gear < lastGear && now > upAt) { gear++; shiftT = .22; shiftDir = 1 }
                else if (gear > 0 && now < downAt && rpmAt(v, gear - 1) < profile.redline - 600) { gear--; shiftT = .16; shiftDir = -1 }
            }
            if (v < .4 && load < .05) gear = 0
            var tgt = max(profile.idle.toDouble(), rpmAt(v, gear))
            if (gear == 0 && v < 7) tgt = max(tgt, profile.idle + load * 2600 * (1 - v / 7))
            tgt = min(tgt, profile.redline.toDouble())
            rpm += (tgt - rpm) * min(1.0, dt * (if (shifting) 9 else 14))
            if (rpm >= profile.redline - 30 && load > .5) rpm -= rnd.next() * 180
        }
        jit += (rnd.next() - .5) * dt * 1.6 - jit * dt * 1.2
        if (load > .04 && load < .98) load = (load + jit * .12).coerceIn(0.0, 1.0)
        val bt = if (profile.isTurbo) load * ((rpm - 1600) / 2400).coerceIn(0.0, 1.0) else 0.0
        boost += (bt - boost) * min(1.0, dt * (if (bt > boost) 2.2 else 6.0))
        val coastT = if (load < .08 && rpm > profile.idle + 600) 1.0 else 0.0
        coast += (coastT - coast) * min(1.0, dt * (if (coastT > 0) 10 else 4))
        pop = load < .05 && rpm > 2600 && rnd.next() < profile.pops * dt * 60 * min(1.4, (rpm - 2600) / 2500)
        blowOff = profile.isTurbo && lastLoad >= .3 && load < .1 && boost > .4
        lastLoad = load
    }

    companion object {
        /** Wheel circumference in metres. */
        const val WHEEL = 2.05
        /** Electric power treated as full throttle. */
        const val FULL_LOAD_KW = 120.0

        fun demoThrottle(t: Double): Double = when {
            t < .9 -> 0.0
            t < 1.7 -> 1.0
            t < 2.6 -> 0.0
            t < 3.0 -> .8
            t < 3.6 -> 0.0
            t < 4.9 -> 1.0
            else -> 0.0
        }
    }
}
