package com.bydmate.app.data.backup

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * WATTSON fork: second copy of every backup on a plugged-in SD card or USB stick.
 *
 * A BYD software update or a factory reset can wipe the head unit's own storage (and with it the
 * Download folder the backups go to). The card survives that, so the trip log and settings can be
 * restored from it afterwards. The app's own folder on the card needs no storage permission; on a
 * computer it is Android/data/<package>/files/Sicherungen.
 */
object BackupMirror {
    private const val TAG = "BackupMirror"
    private const val FOLDER = "Sicherungen"

    /** Automatic copies kept on the card; manual ones are never deleted. */
    const val KEEP_AUTO = 10

    /** Backup folder on the removable volume, or null when no card or stick is mounted. */
    fun dir(context: Context): File? {
        val removable = context.getExternalFilesDirs(null).filterNotNull().firstOrNull { dir ->
            runCatching {
                Environment.isExternalStorageRemovable(dir) &&
                    Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
            }.getOrDefault(false)
        } ?: return null
        return File(removable, FOLDER).apply { mkdirs() }
    }

    /** Copies [file] to the card and trims old automatic copies; returns the copy or null. */
    fun copy(context: Context, file: File): File? {
        val target = dir(context) ?: run {
            Log.i(TAG, "no SD card or USB stick, ${file.name} stays internal only")
            return null
        }
        return copyTo(target, file)
    }

    /** Pure file part of [copy], separate for tests. */
    // Any failure (card pulled, full, read-only) only costs the extra copy.
    @Suppress("TooGenericExceptionCaught")
    internal fun copyTo(dir: File, file: File): File? = try {
        val copy = file.copyTo(File(dir, file.name), overwrite = true)
        copy.setLastModified(file.lastModified())
        rotate(dir, copy)
        Log.i(TAG, "copied ${file.name} to ${dir.absolutePath}")
        copy
    } catch (e: Exception) {
        // A full or read-only card must never fail the backup itself.
        Log.w(TAG, "copy of ${file.name} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun rotate(dir: File, fresh: File) {
        val auto = dir.listFiles { f ->
            f.isFile && f.name.startsWith(AutoBackupRunner.AUTO_PREFIX) && f.name.endsWith(".zip") && f.name != fresh.name
        } ?: return
        auto.sortedByDescending { it.lastModified() }.drop(KEEP_AUTO - 1).forEach { it.delete() }
    }
}
