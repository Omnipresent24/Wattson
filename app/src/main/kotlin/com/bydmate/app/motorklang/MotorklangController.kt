package com.bydmate.app.motorklang

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bydmate.app.data.remote.DiParsData

/**
 * WATTSON fork: Motorklang settings and the decision when the sound plays.
 *
 * Plays while the switch is on and the car is ready to drive (power state DRIVE). With "only
 * electric" (default) it fades out while the Seal U DM-i runs in hybrid mode, where the real
 * petrol engine may be running. Fed by the 1 s vehicle poll of TrackingService.
 */
object MotorklangController {
    const val PREFS = "wattson_motorklang"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROFILE = "profile"
    private const val KEY_VOLUME = "volume"
    private const val KEY_OWN_VMAX = "own_vmax"
    private const val KEY_ONLY_EV = "only_ev"
    const val DEFAULT_OWN_VMAX = 175 // BYD Seal U DM-i
    private const val TAG = "Motorklang"
    private const val DEMO_MS = 7_000L

    private const val POWER_DRIVE = 2
    private const val WORK_MODE_HEV = 3

    data class Settings(
        val enabled: Boolean,
        val profileId: String,
        val volume: Float,
        val ownVmaxKmh: Int,
        val onlyEv: Boolean,
    )

    private var player: MotorklangPlayer? = null
    private var demoActive = false
    private val main = Handler(Looper.getMainLooper())

    fun settings(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            profileId = EngineProfiles.byId(p.getString(KEY_PROFILE, null)).id,
            volume = p.getFloat(KEY_VOLUME, .75f),
            ownVmaxKmh = p.getInt(KEY_OWN_VMAX, DEFAULT_OWN_VMAX),
            onlyEv = p.getBoolean(KEY_ONLY_EV, true),
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putString(KEY_PROFILE, s.profileId)
            .putFloat(KEY_VOLUME, s.volume)
            .putInt(KEY_OWN_VMAX, s.ownVmaxKmh)
            .putBoolean(KEY_ONLY_EV, s.onlyEv)
            .apply()
        synchronized(this) {
            val pl = player ?: return
            // Profile or car changed: rebuild; volume alone: adjust live.
            if (pl.sim.profile.id != s.profileId || !s.enabled && !demoActive) {
                pl.stop(); player = null
            } else pl.volume = s.volume
        }
    }

    /** Whether the sound should play for this snapshot; pure for tests. */
    internal fun shouldPlay(s: Settings, data: DiParsData): Boolean =
        s.enabled && data.powerState == POWER_DRIVE && !(s.onlyEv && data.workMode == WORK_MODE_HEV)

    /** Called once per vehicle poll. */
    fun onPollSnapshot(context: Context, data: DiParsData) {
        synchronized(this) { snapshotLocked(context, data) }
    }

    private fun snapshotLocked(context: Context, data: DiParsData) {
        if (demoActive) return
        val s = settings(context)
        val play = shouldPlay(s, data)
        val current = player
        if (play) {
            val pl = current?.takeIf { it.isRunning } ?: MotorklangPlayer(EngineProfiles.byId(s.profileId), s.ownVmaxKmh, s.volume)
                .also { player = it; it.start(); Log.i(TAG, "on: ${s.profileId}") }
            pl.onCarReading(data.speed, data.power)
        } else if (current != null) {
            Log.i(TAG, "off: enabled=${s.enabled} power=${data.powerState} work=${data.workMode}")
            current.stop(); player = null
        }
    }

    /** "Hörprobe" in Settings: revs the chosen engine for a few seconds, also when parked. */
    fun preview(context: Context): Unit = synchronized(this) {
        val s = settings(context)
        player?.stop()
        val pl = MotorklangPlayer(EngineProfiles.byId(s.profileId), s.ownVmaxKmh, s.volume)
        player = pl
        demoActive = true
        pl.start()
        pl.startDemo()
        main.postDelayed({
            synchronized(this) {
                if (player === pl) { pl.stop(); player = null }
                demoActive = false
            }
        }, DEMO_MS)
    }

    fun stopAll(): Unit = synchronized(this) {
        player?.stop(); player = null; demoActive = false
    }
}
