package com.novacami.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt
import java.util.concurrent.Executors

enum class BgMode(val label: String) { OFF("Background: Off"), BLUR("Background: Portrait blur"), GREEN("Background: Green screen"), BLACK("Background: Black"), IMAGE("Background: My picture") }
enum class WbMode(val label: String, val mode: Int) {
    AUTO("Auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    TUNGSTEN("Tungsten", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescent", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
}
enum class NoiseMode(val label: String) { AUTO("Auto"), OFF("Off"), FAST("Fast"), HIGH("High quality") }

/**
 * Owns the whole pipeline: camera -> GPU -> hardware H.264 -> network.
 * Heat/lag protections: no CPU pixel work, bounded per-viewer queues, effects pause when the
 * phone gets warm, bitrate drops when it gets hot, optional preview-off + dimmed screen.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class StreamEngine(ctx: Context, port: Int) {
    private val app: Context = ctx.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // ----- settings (shared with UI, desktop app and presets) -----
    @Volatile var width = 1280
    @Volatile var height = 720
    @Volatile var fps = 30; private set
    @Volatile var bitrateMbps = 4f
    @Volatile var bg = BgMode.OFF
    @Volatile var noise = NoiseMode.AUTO
    @Volatile var autoIso = true
    @Volatile var iso = 400
    @Volatile var mirror = false
    @Volatile var front = false
    @Volatile var previewHidden = false
    /** Phone held upright: stream the whole 3:4 picture (like the Samsung camera app) instead of a cropped 16:9 strip. */
    @Volatile var portraitOut = false
    val outW: Int get() = if (portraitOut) (if (height >= 1080) 1152 else 720) else width
    val outH: Int get() = if (portraitOut) (if (height >= 1080) 1536 else 960) else height
    @Volatile var previewFull = true   // portrait: show the whole camera picture, framed where the PC picture is
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    @Volatile var rotationFix = 0      // manual picture rotation fix, 0/90/180/270 (control?rotfix=)
    // camera framing / look (zoom is remembered separately for the rear and front camera)
    @Volatile var zoomRear = 1f
    @Volatile var zoomFront = 1f
    @Volatile var zoomMin = 1f
    @Volatile var zoomMax = 1f
    @Volatile var ev = 0f              // exposure compensation in stops
    @Volatile var evMin = 0f
    @Volatile var evMax = 0f
    @Volatile var evStep = 0f
    @Volatile var wb = WbMode.AUTO
    @Volatile var torch = false
    @Volatile var hasTorch = false
    private var awbModes: IntArray? = null
    val zoom: Float get() = if (front) zoomFront else zoomRear
    @Volatile var isoMin = 100
    @Volatile var isoMax = 3200

    // ----- live state -----
    @Volatile var streaming = false
    @Volatile var guard = false
    @Volatile var battTemp = 0f
    @Volatile var battPct = 0
    @Volatile var thermalName = "NONE"
    @Volatile var lastFps = 0
    @Volatile var lastMbps = 0f
    /** Be right back: the PC sees a "Be right back" card instead of the camera. */
    @Volatile var brb = false
    /** The user's own background picture (decoded, at most 1280 px on the long side). */
    @Volatile var bgPicture: Bitmap? = null
    val hasBgPicture: Boolean get() = bgPicture != null
    var onChanged: (() -> Unit)? = null
    /** Used by the foreground service (the screen has [onChanged]). */
    var serviceListener: (() -> Unit)? = null
    @Volatile private var restarting = false
    private fun notifyChanged() { onChanged?.invoke(); if (!restarting) serviceListener?.invoke() }
    var onError: ((String) -> Unit)? = null

    private var enc: H264Encoder? = null
    private var pipe: GlPipeline? = null
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var fpsRanges: Array<Range<Int>>? = null
    private var prevSurface: Surface? = null
    private var prevW = 640
    private var prevH = 360
    private var bitrateCut = false
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val segmenter = Segmenter(analysisExec)
    private val mainExec = ContextCompat.getMainExecutor(app)
    private val discovery = Discovery(port, Build.MODEL)   // lets the PC find the phone, also with the screen off

    // The camera is bound to this owner, not to the screen, so it keeps running when the app is in the background.
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    val pins = PinStore(app)
    val server = StreamServer(port, { statusJson() }, { handleControl(it) }, { enc?.requestKeyFrame() }, { pins.check(it) })


    private fun displaySurfaceRotation(): Int = try {
        (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    } catch (e: Exception) { Surface.ROTATION_0 }

    /** Rotation of the screen from the phone's natural orientation, counter-clockwise degrees. */
    private fun displayRotationDegrees(): Int {
        val r = displaySurfaceRotation()
        return when (r) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    }

    // keep the picture upright if the phone is turned from one landscape side to the other
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            pipe?.rotationDegrees = displayRotationDegrees()
            // upright <-> sideways changes the picture shape (3:4 <-> 16:9): restart the encoder with the new size
            val nowPortrait = displayRotationDegrees() % 180 == 0
            if (streaming && nowPortrait != portraitOut) { restart(); notifyChanged() }
        }
    }

    private val thermalNames = listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
    private val monitor = object : Runnable {
        override fun run() { poll(); mainHandler.postDelayed(this, 1000) }
    }

    init {
        segmenter.onMask = { d, w, h -> pipe?.submitMask(d, w, h) }
        (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
        lifecycleOwner.registry.currentState = Lifecycle.State.STARTED
        server.start()
        discovery.start()
        mainHandler.post(monitor)
        loadSavedPicture()
    }

    // ----- battery / thermal / stats, once a second (runs with the screen off too) -----

    private fun poll() {
        try {
            val i: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val pct = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val th = if (Build.VERSION.SDK_INT >= 29) (app.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0
            battTemp = temp; battPct = pct; thermalName = thermalNames.getOrElse(th) { "?" }
            updateThermal(temp, th)
            sampleStats()
        } catch (e: Exception) { Log.w(TAG, "poll", e) }
    }

    // ----- lifecycle -----
    /** Keeps the Wi-Fi radio out of power-save while streaming (power-save adds jitter and lag spikes on most phones, Samsung included). */
    @Suppress("DEPRECATION")
    private fun holdWifi(on: Boolean) {
        try {
            if (on) {
                if (wifiLock == null) {
                    val wm = app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                               else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiLock = wm.createWifiLock(mode, "novacami-stream").also { it.setReferenceCounted(false) }
                }
                wifiLock?.acquire()
            } else {
                wifiLock?.let { if (it.isHeld) it.release() }
            }
        } catch (e: Exception) { Log.w(TAG, "wifi lock", e) }
    }

    fun start() {
        if (streaming) return
        streaming = true
        holdWifi(true)
        portraitOut = displayRotationDegrees() % 180 == 0
        try {
            val e = H264Encoder(outW, outH, fps, (bitrateMbps * 1_000_000).toInt()) { d, k -> server.broadcast(d, k) }
            e.start(); enc = e
            server.resetClients()
            val p = GlPipeline(e.inputSurface, outW, outH)
            p.rotationDegrees = displayRotationDegrees(); p.rotationFix = rotationFix
            p.seg = segmenter   // the GPU feeds the segmenter with the exact picture it draws (mask always lines up)
            p.start(); pipe = p
            p.setPreviewSurface(prevSurface, prevW, prevH)
            p.setBackgroundBitmap(bgPicture)
            p.setBrbBitmap(BrbCard.create(outW, outH))
            applyEffects()
            bindCamera()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            stop()
            onError?.invoke(t.message ?: t.toString())
        }
        notifyChanged()
    }

    fun stop() {
        streaming = false
        holdWifi(false)
        try { provider?.unbindAll() } catch (_: Exception) { }
        camera = null
        pipe?.release(); pipe = null
        enc?.stop(); enc = null
        bitrateCut = false
        lastFps = 0; lastMbps = 0f
        notifyChanged()
    }

    fun restart() { restarting = true; try { stop(); start() } finally { restarting = false } }

    fun shutdown() {
        stop(); server.stop(); discovery.stop(); analysisExec.shutdown()
        mainHandler.removeCallbacks(monitor)
        try { (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener) } catch (_: Exception) { }
    }

    fun setPreviewSurface(s: Surface?, w: Int, h: Int) {
        prevSurface = s; prevW = w; prevH = h
        pipe?.setPreviewSurface(s, w, h)
    }

    /** Apply changed settings: some need a restart (size/fps/bitrate/camera), the rest are live. */
    fun commit(restart: Boolean = false) {
        if (restart && streaming) restart() else { applyEffects(); applyCamera() }
        notifyChanged()
    }

    // ----- camera -----
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            if (!streaming) return@addListener
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val rot = displaySurfaceRotation()

                // Upright phone: ask for a 4:3 camera picture = the sensor's full field of view (what the camera app shows).
                val tall = portraitOut
                fun sel(w: Int, h: Int) = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(if (tall) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                                            else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()

                val preview = Preview.Builder().setResolutionSelector(if (tall) sel(outH, outW) else sel(width, height)).setTargetRotation(rot).build()
                preview.setSurfaceProvider(mainExec) { req ->
                    val pl = pipe
                    if (pl == null) { req.willNotProvideSurface(); return@setSurfaceProvider }
                    pl.surfaceTexture.setDefaultBufferSize(req.resolution.width, req.resolution.height)
                    pl.camW = req.resolution.width; pl.camH = req.resolution.height
                    pl.rotationDegrees = displayRotationDegrees()
                    req.provideSurface(pl.cameraSurface, mainExec) { }
                }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                camera = p.bindToLifecycle(lifecycleOwner, selector, preview)
                readCameraInfo()
                applyCamera()
                notifyChanged()
            } catch (t: Throwable) {
                Log.e(TAG, "bind failed", t)
                onError?.invoke(t.message ?: t.toString())
            }
        }, mainExec)
    }

    private fun readCameraInfo() {
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            isoMin = it.lower; isoMax = it.upper; iso = iso.coerceIn(isoMin, isoMax)
        }
        fpsRanges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        awbModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)

        // zoom range of the camera that is bound now (below 1.0x = wide angle, on phones that offer it)
        val zs = cam.cameraInfo.zoomState.value
        zoomMin = zs?.minZoomRatio ?: 1f
        zoomMax = minOf(zs?.maxZoomRatio ?: 1f, 10f)
        if (front) zoomFront = zoomFront.coerceIn(zoomMin, zoomMax) else zoomRear = zoomRear.coerceIn(zoomMin, zoomMax)

        val es = cam.cameraInfo.exposureState
        if (es.isExposureCompensationSupported) {
            evStep = es.exposureCompensationStep.toFloat()
            evMin = es.exposureCompensationRange.lower * evStep
            evMax = es.exposureCompensationRange.upper * evStep
        } else { evStep = 0f; evMin = 0f; evMax = 0f }
        ev = ev.coerceIn(evMin, evMax)
        hasTorch = cam.cameraInfo.hasFlashUnit()
    }

    fun setZoom(v: Float) {
        val c = v.coerceIn(zoomMin, zoomMax)
        if (front) zoomFront = c else zoomRear = c
    }

    fun resetCamera() {
        zoomRear = 1f; zoomFront = 1f; ev = 0f; wb = WbMode.AUTO; torch = false
    }

    private fun pickFpsRange(r: Array<Range<Int>>?, target: Int): Range<Int>? {
        if (r == null) return null
        return r.firstOrNull { it.lower == target && it.upper == target }
            ?: r.filter { it.upper == target }.maxByOrNull { it.lower }
            ?: r.filter { it.upper < target }.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
    }

    fun applyCamera() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        when (noise) {
            NoiseMode.AUTO -> {}
            NoiseMode.OFF -> b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            NoiseMode.FAST -> b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            NoiseMode.HIGH -> b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }
        pickFpsRange(fpsRanges, fps)?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (!autoIso) {
            val frameNs = 1_000_000_000L / fps
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, minOf(20_000_000L, frameNs))
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        }
        if (wb != WbMode.AUTO && awbModes?.contains(wb.mode) == true)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb.mode)
        cam.cameraControl.setZoomRatio(zoom.coerceIn(zoomMin, zoomMax))
        if (evStep > 0f && evMax > evMin) cam.cameraControl.setExposureCompensationIndex((ev / evStep).roundToInt())
        cam.cameraControl.enableTorch(hasTorch && torch && !front)
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    // ----- effects + thermal guard -----
    fun applyEffects() {
        val active = bg != BgMode.OFF && !guard
        val p = pipe
        if (p != null) {
            p.mode = if (active) when (bg) { BgMode.BLUR -> 1; BgMode.GREEN -> 2; BgMode.BLACK -> 3; BgMode.IMAGE -> if (bgPicture != null) 4 else 3; else -> 0 } else 0
            p.brb = brb
            p.mirror = mirror
            p.previewOn = !previewHidden
            p.previewFull = previewFull
            if (!active) p.invalidateMask()
        }
        segmenter.enabled = active && !brb   // nothing to cut out while the Be right back card is shown
    }

    fun updateThermal(tempC: Float, thermal: Int) {
        // Background effects stay on up to GUARD_ON_C (battery temperature) or Android's SEVERE thermal status.
        val hot = thermal >= 3 || tempC >= GUARD_ON_C
        val cool = thermal <= 2 && tempC <= GUARD_OFF_C
        if (!guard && hot) { guard = true; applyEffects(); notifyChanged() }
        else if (guard && cool) { guard = false; applyEffects(); notifyChanged() }
        val severe = thermal >= 3 || tempC >= 45f
        if (severe && !bitrateCut) { bitrateCut = true; enc?.setBitrate((bitrateMbps * 500_000).toInt()) }
        else if (!severe && bitrateCut && cool) { bitrateCut = false; enc?.setBitrate((bitrateMbps * 1_000_000).toInt()) }
    }

    /** (fps, Mbps) over the last sampling interval (call once per second). */
    fun sampleStats(): Pair<Int, Float> {
        val s = enc?.takeStats() ?: Pair(0, 0L)
        lastFps = s.first
        lastMbps = s.second * 8f / 1_000_000f
        return Pair(lastFps, lastMbps)
    }

    // ----- presets -----
    private fun autoBitrate() {
        bitrateMbps = when {
            height >= 1080 && fps >= 60 -> 10f
            height >= 1080 -> 7f
            fps >= 60 -> 6f
            else -> 4f
        }
    }

    fun applyPreset(name: String) {
        when (name) {
            "quality" -> { width = 1920; height = 1080; fps = 30; bitrateMbps = 8f; noise = NoiseMode.HIGH; previewHidden = false }
            "latency" -> { width = 1280; height = 720; fps = 30; bitrateMbps = 4f; noise = NoiseMode.AUTO; bg = BgMode.OFF; previewHidden = false }
            "battery" -> { width = 1280; height = 720; fps = 30; bitrateMbps = 2.5f; noise = NoiseMode.AUTO; bg = BgMode.OFF; previewHidden = true }
        }
    }

    fun setResolution(h: Int) {
        if (h >= 1080) { width = 1920; height = 1080 } else { width = 1280; height = 720 }
        autoBitrate()
    }

    fun setFps(v: Int) { fps = v; autoBitrate() }

    // ----- remote control from the PC app -----
    private fun handleControl(p: Map<String, String>): String {
        mainHandler.post {
            var restart = false
            p["bg"]?.let { v -> BgMode.values().firstOrNull { it.name.equals(v, true) }?.let { bg = it } }
            p["noise"]?.let { v -> NoiseMode.values().firstOrNull { it.name.equals(v, true) }?.let { noise = it } }
            p["denoise"]?.let { noise = if (it == "1") NoiseMode.HIGH else NoiseMode.OFF }
            p["mirror"]?.let { mirror = it == "1" }
            p["brb"]?.let { brb = it == "1" }
            p["auto"]?.let { autoIso = it == "1" }
            p["iso"]?.toIntOrNull()?.let { iso = it.coerceIn(isoMin, isoMax) }
            p["preview"]?.let { previewHidden = it == "0" }
            p["previewfull"]?.let { previewFull = it == "1" }
            p["rotfix"]?.toIntOrNull()?.let { rotationFix = (((it % 360) + 360) % 360) / 90 * 90; pipe?.rotationFix = rotationFix }
            p["zoom"]?.toFloatOrNull()?.let { setZoom(it) }
            p["ev"]?.toFloatOrNull()?.let { ev = it.coerceIn(evMin, evMax) }
            p["wb"]?.let { v -> WbMode.values().firstOrNull { it.name.equals(v, true) }?.let { wb = it } }
            p["torch"]?.let { torch = it == "1" }
            if (p["camreset"] == "1") resetCamera()
            p["res"]?.toIntOrNull()?.let { val old = height; setResolution(it); if (old != height) restart = true }
            p["fps"]?.toIntOrNull()?.let { if ((it == 30 || it == 60) && it != fps) { setFps(it); restart = true } }
            p["bitrate"]?.toFloatOrNull()?.let { bitrateMbps = it.coerceIn(1f, 20f); restart = true }
            p["preset"]?.let { applyPreset(it); restart = true }
            if (p["flip"] == "1") { front = !front; restart = true }
            when (p["stream"]) { "0" -> stop(); "1" -> start() }
            commit(restart)
        }
        return """{"ok":true}"""
    }

    fun statusJson(): String {
        val mb = String.format(Locale.US, "%.2f", lastMbps)
        val zs = String.format(Locale.US, "%.2f", zoom)
        val zmin = String.format(Locale.US, "%.2f", zoomMin)
        val zmax = String.format(Locale.US, "%.2f", zoomMax)
        val evs = String.format(Locale.US, "%.2f", ev)
        return """{"batteryTempC":$battTemp,"batteryPct":$battPct,"thermal":"$thermalName","fps":$lastFps,"mbps":$mb,""" +
            """"viewers":${server.viewers},"codec":"h264","width":$width,"height":$height,"outWidth":$outW,"outHeight":$outH,"portrait":$portraitOut,"targetFps":$fps,"bitrateMbps":$bitrateMbps,""" +
            """"bg":"${bg.name}","noise":"${noise.name}","autoIso":$autoIso,"iso":$iso,"isoMin":$isoMin,"isoMax":$isoMax,""" +
            """"mirror":$mirror,"front":$front,"guard":$guard,"streaming":$streaming,"previewHidden":$previewHidden,"rotfix":$rotationFix,""" +
            """"zoom":$zs,"zoomMin":$zmin,"zoomMax":$zmax,"ev":$evs,"wb":"${wb.name}","torch":$torch,"hasTorch":$hasTorch,"brb":$brb,"hasBgPicture":$hasBgPicture}"""
    }

    // ----- be right back -----
    fun showBrb(on: Boolean) { brb = on; applyEffects(); notifyChanged() }

    // ----- the user's own background picture -----
    private val pictureFile get() = File(app.filesDir, "bg_picture.jpg")

    private fun loadSavedPicture() {
        analysisExec.execute {
            try {
                val f = pictureFile
                if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { bgPicture = it; pipe?.setBackgroundBitmap(it) }
            } catch (e: Exception) { Log.w(TAG, "load picture", e) }
        }
    }

    /** Reads the chosen picture (any size, any phone rotation), keeps a small copy, and gives it to the GPU. */
    fun setBackgroundPicture(uri: Uri, done: (Boolean) -> Unit) {
        analysisExec.execute {
            var ok = false
            try {
                val bmp = decodePicture(uri, 1280) ?: throw IllegalStateException("cannot read this picture")
                pictureFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                bgPicture = bmp
                pipe?.setBackgroundBitmap(bmp)
                ok = true
            } catch (e: Exception) { Log.w(TAG, "background picture", e) }
            mainHandler.post { done(ok) }
        }
    }

    private fun decodePicture(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = exifRotation(uri)   // phone photos are often stored sideways with a rotation tag
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val big = maxOf(bmp.width, bmp.height)
        if (big > maxSide) {
            val k = maxSide.toFloat() / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        }
        return bmp
    }

    private fun exifRotation(uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return try {
            app.contentResolver.openInputStream(uri)?.use { st ->
                when (android.media.ExifInterface(st).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90; 3 -> 180; 8 -> 270; else -> 0
                }
            } ?: 0
        } catch (e: Exception) { 0 }
    }

    companion object {
        private const val TAG = "NovacamiEngine"
        /** Effects pause at this battery temperature and resume once it drops to GUARD_OFF_C. */
        private const val GUARD_ON_C = 44f
        private const val GUARD_OFF_C = 42.5f
    }
}
