package com.bydmate.app.camera

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * WATTSON fork: camera lab for the Seal U DM-i.
 *
 * 1. Steps through the preview channels of the AVM camera so the driver can tell which channel
 *    is front, rear, left and right on this car (they differ between BYD models).
 * 2. Test recording: front and rear channel into two encoders at once, 15 s buffered, then
 *    saved together with 15 s more — the same path the accident recording will take, so it
 *    shows whether the head unit copes and whether the card is writable.
 *
 * Run it parked, without a turn signal on: the blind-spot pipeline uses the same camera.
 */
@Suppress("TooManyFunctions")
class CameraLabActivity : Activity(), TextureView.SurfaceTextureListener {

    private val camera = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val probe = AvmCameraProbe()

    private lateinit var status: TextView
    private lateinit var channelLabel: TextView
    private lateinit var result: TextView
    private lateinit var texture: TextureView
    private var previewSurface: Surface? = null
    private var channel = 0
    @Volatile private var recording = false

    private val prefs get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(pad, pad, pad, pad)
        }
        texture = TextureView(this).apply { surfaceTextureListener = this@CameraLabActivity }
        root.addView(texture, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, 0, 0)
        }
        fun text(size: Float) = TextView(this).apply { setTextColor(Color.WHITE); textSize = size }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        side.addView(text(20f).apply { text = getString(com.bydmate.app.R.string.wattson_lab_title) })
        status = text(13f)
        side.addView(status)
        channelLabel = text(26f).apply { gravity = Gravity.CENTER }
        val stepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stepRow.addView(button("◀") { showChannel(channel - 1) })
        stepRow.addView(channelLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stepRow.addView(button("▶") { showChannel(channel + 1) })
        side.addView(stepRow)
        side.addView(button(getString(com.bydmate.app.R.string.wattson_lab_set_front)) { assign(KEY_FRONT) })
        side.addView(button(getString(com.bydmate.app.R.string.wattson_lab_set_rear)) { assign(KEY_REAR) })
        side.addView(button(getString(com.bydmate.app.R.string.wattson_lab_record)) { testRecording() })
        side.addView(button(getString(com.bydmate.app.R.string.wattson_lab_close)) { finish() })
        result = text(13f)
        side.addView(result)
        root.addView(ScrollView(this).apply { addView(side) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        setContentView(root)

        camera.execute {
            val found = probe.discover()
            main.post {
                updateStatus(if (found) null else probe.discoverJournal.joinToString("\n"))
                if (found) showChannel(prefs.getInt(KEY_FRONT, 0))
            }
        }
    }

    private fun updateStatus(error: String?) {
        val target = RecordingStorage.target(this)
        status.text = buildString {
            if (error != null) {
                append(getString(com.bydmate.app.R.string.wattson_lab_no_camera)).append('\n').append(error)
                return@buildString
            }
            append("Kamera-ID ${probe.cameraId}, ${probe.previewWidth}×${probe.previewHeight}\n")
            val cluster = blindSpotClusterDisplay(this@CameraLabActivity)
            append(if (cluster != null) "Kombiinstrument-Anzeige: gefunden (${cluster.name})\n"
                else "Kombiinstrument-Anzeige: NICHT gefunden\n")
            append("Front = Kanal ${prefs.getInt(KEY_FRONT, 0)}, Heck = Kanal ${prefs.getInt(KEY_REAR, 1)}\n")
            append(if (target.removable) "Speicherkarte: " else "Interner Speicher: ")
            append("${target.dir.absolutePath}\n")
            append("frei: ${target.freeBytes / 1_000_000} MB")
        }
    }

    private fun showChannel(index: Int) {
        if (recording || probe.cameraId < 0) return
        channel = index.coerceIn(0, MAX_CHANNEL)
        channelLabel.text = "Kanal $channel"
        val surface = previewSurface ?: return
        camera.execute {
            probe.close("lab switch")
            val ok = probe.open(channel, surface)
            main.post { if (!ok) result.text = "Kanal $channel: kein Bild (vom System abgelehnt)" else result.text = "" }
        }
    }

    private fun assign(key: String) {
        prefs.edit().putInt(key, channel).apply()
        updateStatus(null)
    }

    /** Front and rear into two encoders: 15 s buffer, then save it plus 15 s more. */
    private fun testRecording() {
        if (recording || probe.cameraId < 0) return
        recording = true
        val front = prefs.getInt(KEY_FRONT, 0)
        val rear = prefs.getInt(KEY_REAR, 1)
        result.text = getString(com.bydmate.app.R.string.wattson_lab_recording)
        camera.execute {
            val log = StringBuilder()
            val target = RecordingStorage.target(this)
            val w = probe.previewWidth.takeIf { it > 0 } ?: FALLBACK_W
            val h = probe.previewHeight.takeIf { it > 0 } ?: FALLBACK_H
            val encoders = listOf("front" to front, "rear" to rear).mapNotNull { (name, index) ->
                val enc = ClipEncoder(name)
                // Full camera size first; a smaller standard size if the encoder refuses it.
                val surface = enc.start(w, h) ?: enc.start(FALLBACK_W, FALLBACK_H)
                if (surface == null) {
                    log.append("$name: Encoder abgelehnt\n")
                    null
                } else {
                    log.append("$name (Kanal $index): ${enc.width}×${enc.height}\n")
                    Triple(name, index, enc)
                }
            }
            probe.close("lab record")
            val opened = encoders.isNotEmpty() &&
                probe.openWarm(encoders.mapNotNull { (_, index, enc) -> enc.inputSurface?.let { index to it } }.toMap())
            if (!opened) log.append("Kamera hat die Aufnahme-Kanäle abgelehnt\n")
            sleepQuietly(BUFFER_MS)
            val started = System.currentTimeMillis()
            encoders.forEach { (name, _, enc) ->
                val file = RecordingStorage.clipFile(target.dir, "test", name, started)
                if (!enc.startSaving(file)) log.append("$name: keine Bilder erhalten, nichts gespeichert\n")
            }
            sleepQuietly(AFTER_MS)
            encoders.forEach { (name, _, enc) ->
                val frames = enc.framesEncoded
                val seconds = enc.savedDurationUs / 1_000_000.0
                val file = enc.stopSaving()
                enc.stop()
                log.append("$name: $frames Bilder, %.1f s".format(seconds))
                if (file != null) log.append(", ${file.length() / 1000} KB\n  ${file.absolutePath}\n") else log.append('\n')
            }
            probe.close("lab record done")
            main.post {
                recording = false
                result.text = log.toString()
                showChannel(channel)
            }
        }
    }

    private fun sleepQuietly(ms: Long) = runCatching { Thread.sleep(ms) }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        previewSurface = Surface(st)
        if (probe.cameraId >= 0) showChannel(channel)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val surface = previewSurface
        previewSurface = null
        // The vendor may still write into the preview surface: unbind before releasing it. A
        // running test recording is bound to the encoders only and must not block the UI thread.
        if (!recording) {
            runCatching { camera.submit { probe.close("lab surface gone") }.get() }
        }
        surface?.release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onDestroy() {
        runCatching { camera.execute { probe.close("lab closed") } }
        camera.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "wattson_camera"
        const val KEY_FRONT = "front_index"
        const val KEY_REAR = "rear_index"
        private const val MAX_CHANNEL = 7
        private const val FALLBACK_W = 1280
        private const val FALLBACK_H = 720
        private const val BUFFER_MS = 15_000L
        private const val AFTER_MS = 15_000L
    }
}
