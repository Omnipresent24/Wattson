package com.bydmate.app.motorklang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotorklangTest {

    @Test fun `every profile has a valid layout`() {
        assertEquals(18, EngineProfiles.ALL.size)
        EngineProfiles.ALL.forEach { p ->
            p.banks?.let { assertEquals(p.id, p.cylinders, it.size) }
            p.amps?.let { assertEquals(p.id, p.cylinders, it.size) }
            assertTrue(p.id, p.redline > p.idle && p.gears.isNotEmpty() && p.vmax > 100)
        }
        assertEquals("rockcrusher", EngineProfiles.byId("unknown").id)
    }

    @Test fun `standing car idles in neutral`() {
        val sim = EngineSim(EngineProfiles.byId("velvet"), 175)
        sim.onCarReading(0.0, 0.0, 0.0, null)
        repeat(400) { sim.step(.005) }
        assertEquals(0, sim.gear)
        assertEquals(600.0, sim.rpm, 60.0)
    }

    @Test fun `accelerating car climbs through the gears and stays below redline`() {
        val p = EngineProfiles.byId("outlaw")
        val sim = EngineSim(p, 175)
        var t = 0.0
        var speed = 0.0
        var maxGear = 0
        while (t < 30) {
            speed = minOf(150.0, speed + 6.0) // about 6 km/h per second
            sim.onCarReading(speed, 80.0, t + 1, t)
            repeat(200) { sim.step(.005); assertTrue(sim.rpm <= p.redline + 1) }
            maxGear = maxOf(maxGear, sim.gear)
            t += 1
        }
        assertTrue("reached gear $maxGear", maxGear >= 4)
        assertTrue(sim.load > .1)
    }

    @Test fun `regeneration lets the engine coast`() {
        val sim = EngineSim(EngineProfiles.byId("fortress"), 175)
        var t = 0.0
        repeat(10) { sim.onCarReading(100.0, 40.0, t + 1, t); repeat(200) { sim.step(.005) }; t++ }
        repeat(3) { sim.onCarReading(100.0 - it * 5, -20.0, t + 1, t); repeat(200) { sim.step(.005) }; t++ }
        assertTrue("load ${sim.load}", sim.load < .1)
        assertTrue("coast ${sim.coast}", sim.coast > .5)
    }

    @Test fun `rendered sound is finite, audible and within full scale`() {
        EngineProfiles.ALL.forEach { p ->
            val sr = 48000.0
            val sim = EngineSim(p, 175)
            val voice = EngineVoice(sr, p)
            val mixer = MotorklangMixer(sr, p)
            val l = FloatArray(256)
            val r = FloatArray(256)
            sim.startDemo()
            var peak = 0f
            repeat(600) { // 3.2 s of the rev demo
                sim.step(256 / sr)
                voice.targetRpm = sim.rpm; voice.targetLoad = sim.load; voice.targetCoast = sim.coast
                voice.targetCutoff = p.muffler * (1.3 + sim.load * 1.8) + sim.rpm * .3 + 450
                mixer.update(sim, 256)
                voice.render(l, r, 256)
                mixer.mix(l, r, 256)
                for (i in 0 until 256) {
                    assertTrue(p.id, l[i].isFinite() && r[i].isFinite())
                    assertTrue(p.id, abs(l[i]) <= 1f && abs(r[i]) <= 1f)
                    peak = maxOf(peak, abs(l[i]))
                }
            }
            assertTrue("${p.id} peak $peak", peak > .05f)
        }
    }
}
