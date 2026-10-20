package com.bydmate.app.camera

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WATTSON fork: where camera recordings go. A plugged-in SD card or USB stick wins; the app's
 * own folder on it needs no storage permission. Without one the internal storage is used.
 * On a computer the files are under Android/data/<package>/files/Aufnahmen on the card.
 */
object RecordingStorage {
    private const val FOLDER = "Aufnahmen"

    data class Target(val dir: File, val removable: Boolean, val freeBytes: Long)

    fun target(context: Context): Target {
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        val removable = dirs.firstOrNull { dir ->
            runCatching {
                Environment.isExternalStorageRemovable(dir) &&
                    Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
            }.getOrDefault(false)
        }
        val base = removable ?: dirs.firstOrNull() ?: context.filesDir
        val dir = File(base, FOLDER).apply { mkdirs() }
        val free = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
        return Target(dir, removable != null, free)
    }

    /** File name for a clip of [camera] ("front", "rear", ...) started now. */
    fun clipFile(dir: File, kind: String, camera: String, now: Long = System.currentTimeMillis()): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(now))
        return File(dir, "${stamp}_${kind}_$camera.mp4")
    }
}
