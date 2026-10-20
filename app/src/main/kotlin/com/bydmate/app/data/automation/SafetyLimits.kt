package com.bydmate.app.data.automation

import android.content.Context

/**
 * User-adjustable safety limits (WATTSON fork).
 *
 * The door-unlock speed limit used to be a fixed 30 km/h. It is now chosen in Settings from
 * [UNLOCK_OPTIONS_KMH] and kept here as a process-wide value, so the pure gate functions in
 * [ActionDispatcher] can read it without a Context. [load] runs once at app start.
 */
object SafetyLimits {
    private const val PREFS = "wattson_safety"
    private const val KEY_UNLOCK_MAX_KMH = "unlock_max_kmh"

    /** Speeds the user can pick for the door-unlock limit. */
    val UNLOCK_OPTIONS_KMH: List<Int> = listOf(10, 15, 20, 30)
    const val DEFAULT_UNLOCK_MAX_KMH = 15

    /** Door unlocking is blocked above this speed (km/h). */
    @Volatile
    var unlockMaxKmh: Int = DEFAULT_UNLOCK_MAX_KMH
        private set

    fun load(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UNLOCK_MAX_KMH, DEFAULT_UNLOCK_MAX_KMH)
        unlockMaxKmh = stored.takeIf { it in UNLOCK_OPTIONS_KMH } ?: DEFAULT_UNLOCK_MAX_KMH
    }

    fun setUnlockMaxKmh(context: Context, kmh: Int) {
        if (kmh !in UNLOCK_OPTIONS_KMH) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_UNLOCK_MAX_KMH, kmh).apply()
        unlockMaxKmh = kmh
    }
}
