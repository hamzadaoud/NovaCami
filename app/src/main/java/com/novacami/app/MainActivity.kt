package com.novacami.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val port = EngineHost.PORT
    private lateinit var engine: StreamEngine
    private val ui = Handler(Looper.getMainLooper())
    private var uiSync = false

    private lateinit var fpsPill: TextView
    private lateinit var battery: TextView
    private lateinit var surface: SurfaceView
    private lateinit var previewHidden: TextView
    private lateinit var resPill: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvTip: TextView
    private lateinit var tvStats: TextView
    private lateinit var spBg: Spinner
    private lateinit var spNoise: Spinner
    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private lateinit var swMirror: MaterialSwitch
    private lateinit var swAuto: MaterialSwitch
    private lateinit var slider: Slider
    private lateinit var tvIso: TextView
    private lateinit var btnStream: MaterialButton
    private lateinit var sliderZoom: Slider
    private lateinit var tvZoom: TextView
    private lateinit var btnZoomMin: MaterialButton
    private lateinit var btnZoom1: MaterialButton
    private lateinit var btnZoom2: MaterialButton
    private lateinit var sliderEv: Slider
    private lateinit var tvEv: TextView
    private lateinit var spWb: Spinner
    private lateinit var swTorch: MaterialSwitch
    private lateinit var tvBgNote: TextView
    private lateinit var tvPin: TextView
    private lateinit var btnBrb: MaterialButton
    private lateinit var btnBgPic: MaterialButton
    private var adView: AdView? = null
    private lateinit var consent: ConsentManager
    private val adsStarted = AtomicBoolean(false)
    private var adsReady = false

    private val resItems = listOf("1280 × 720 (HD)", "1920 × 1080 (FHD)")
    private val fpsItems = listOf("30 FPS", "60 FPS")

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) { startStreaming(); askNotifications() } else Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show()
    }

    // lets the "streaming" notification show on Android 13+ (streaming works without it)
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // the user's own background picture (system picker: no storage permission needed)
    private val pickPicture = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) engine.setBackgroundPicture(uri) { ok ->
            if (ok) { engine.bg = BgMode.IMAGE; engine.commit() }
            else Toast.makeText(this, "Could not open that picture", Toast.LENGTH_LONG).show()
        }
    }

    /** Streaming runs in a foreground service, so it keeps going with the screen off. */
    private fun startStreaming() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START))
        } catch (e: Exception) {
            engine.start()   // the phone refused the service: stream while the app is open
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("novacami", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Samsung and other phones throttle hard once warm. Sustained-performance mode asks for steady clocks
        // instead of short bursts followed by throttling, which keeps a long stream smooth and cooler.
        if (SUSTAINED_MODE && Build.VERSION.SDK_INT >= 24 &&
            (getSystemService(POWER_SERVICE) as PowerManager).isSustainedPerformanceModeSupported)
            window.setSustainedPerformanceMode(true)

        engine = EngineHost.get(this)
        engine.onChanged = { runOnUiThread { refreshUi() } }
        engine.onError = { msg -> runOnUiThread { Toast.makeText(this, "Error: $msg", Toast.LENGTH_LONG).show() } }
        consent = ConsentManager(this)

        bindViews()

        consent.gather { startAdsIfAllowed() }
        startAdsIfAllowed() // returning users who already answered
        ui.post(loop)
        refreshUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startStreaming(); askNotifications()
        } else permLauncher.launch(Manifest.permission.CAMERA)
    }

    /**
     * The activity handles rotation itself (configChanges in the manifest) so the stream, camera and
     * all settings keep running. We only swap the layout: layout/ = landscape, layout-port/ = portrait.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        adView = null
        bindViews()
        if (adsReady) setupAd()
        refreshUi()
    }

    private var previewHolder: SurfaceHolder? = null
    private var modePill: TextView? = null

    /** Inflate the layout for the current orientation and hook everything up. */
    private fun bindViews() {
        setContentView(R.layout.activity_main)

        // Targeting Android 15/16 (required by Google Play) draws the app edge-to-edge, behind the status and
        // navigation bars. Pad the content by the system-bar and cutout sizes so nothing sits underneath them.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        fpsPill = findViewById(R.id.fpsPill); battery = findViewById(R.id.battery)
        surface = findViewById(R.id.surface); previewHidden = findViewById(R.id.previewHidden)
        resPill = findViewById(R.id.resPill); tvUrl = findViewById(R.id.tvUrl)
        tvTip = findViewById(R.id.tvTip); tvStats = findViewById(R.id.tvStats)
        spBg = findViewById(R.id.spBg); spNoise = findViewById(R.id.spNoise)
        spRes = findViewById(R.id.spRes); spFps = findViewById(R.id.spFps)
        swMirror = findViewById(R.id.swMirror); swAuto = findViewById(R.id.swAuto)
        slider = findViewById(R.id.sliderIso); tvIso = findViewById(R.id.tvIso)
        btnStream = findViewById(R.id.btnStream)
        sliderZoom = findViewById(R.id.sliderZoom); tvZoom = findViewById(R.id.tvZoom)
        btnZoomMin = findViewById(R.id.btnZoomMin); btnZoom1 = findViewById(R.id.btnZoom1); btnZoom2 = findViewById(R.id.btnZoom2)
        sliderEv = findViewById(R.id.sliderEv); tvEv = findViewById(R.id.tvEv)
        spWb = findViewById(R.id.spWb); swTorch = findViewById(R.id.swTorch)
        tvBgNote = findViewById(R.id.tvBgNote)
        tvPin = findViewById(R.id.tvPin)
        btnBrb = findViewById(R.id.btnBrb); btnBgPic = findViewById(R.id.btnBgPic)

        // preview surface (fixed size = cheap for the GPU): square in portrait (the big preview), 16:9 in landscape
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val pw = if (portrait) 720 else 640          // portrait preview is 5:6 - keep in step with layout-port previewBox ratio
        val ph = if (portrait) 864 else 360
        surface.holder.setFixedSize(pw, ph)
        modePill = findViewById(R.id.modePill)
        val toggle = View.OnClickListener {
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                engine.previewFull = !engine.previewFull; engine.commit()
            }
        }
        surface.setOnClickListener(toggle); modePill?.setOnClickListener(toggle)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { previewHolder = h; engine.setPreviewSurface(h.surface, pw, ph) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { engine.setPreviewSurface(h.surface, w, hh) }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                // after a rotation the old view can be destroyed after the new one was created: ignore it then
                if (previewHolder === h) { previewHolder = null; engine.setPreviewSurface(null, 0, 0) }
            }
        })

        spinner(spBg, BgMode.values().map { it.label }) { i ->
            val v = BgMode.values()[i]
            if (v != engine.bg) {
                engine.bg = v; engine.commit()
                if (v == BgMode.IMAGE && !engine.hasBgPicture) pickPicture.launch("image/*")   // first time: choose the picture
            }
        }
        btnBgPic.setOnClickListener { pickPicture.launch("image/*") }
        btnBrb.setOnClickListener { engine.showBrb(!engine.brb) }
        spinner(spNoise, NoiseMode.values().map { it.label }) { i -> val v = NoiseMode.values()[i]; if (v != engine.noise) { engine.noise = v; engine.commit() } }
        spinner(spRes, resItems) { i -> val h = if (i == 1) 1080 else 720; if (h != engine.height) { engine.setResolution(h); engine.commit(true) } }
        spinner(spFps, fpsItems) { i -> val f = if (i == 1) 60 else 30; if (f != engine.fps) { engine.setFps(f); engine.commit(true) } }

        spinner(spWb, WbMode.values().map { it.label }) { i -> val v = WbMode.values()[i]; if (v != engine.wb) { engine.wb = v; engine.commit() } }
        sliderZoom.addOnChangeListener { _, v, fromUser -> if (fromUser) { engine.setZoom(v); tvZoom.text = zoomText(engine.zoom); engine.applyCamera() } }
        sliderEv.addOnChangeListener { _, v, fromUser -> if (fromUser) { engine.ev = v; tvEv.text = evText(v); engine.applyCamera() } }
        btnZoomMin.setOnClickListener { engine.setZoom(engine.zoomMin); engine.commit() }
        btnZoom1.setOnClickListener { engine.setZoom(1f); engine.commit() }
        btnZoom2.setOnClickListener { engine.setZoom(2f); engine.commit() }
        swTorch.setOnCheckedChangeListener { _, c -> if (!uiSync && c != engine.torch) { engine.torch = c; engine.commit() } }
        findViewById<MaterialButton>(R.id.btnCamReset).setOnClickListener { engine.resetCamera(); engine.commit() }

        swMirror.setOnCheckedChangeListener { _, c -> if (!uiSync && c != engine.mirror) { engine.mirror = c; engine.commit() } }
        swAuto.setOnCheckedChangeListener { _, c -> if (!uiSync && c != engine.autoIso) { engine.autoIso = c; engine.commit() } }
        slider.addOnChangeListener { _, v, fromUser -> if (fromUser) { engine.iso = v.toInt(); tvIso.text = v.toInt().toString(); engine.applyCamera() } }

        findViewById<MaterialButton>(R.id.btnFlip).setOnClickListener { engine.front = !engine.front; engine.commit(true) }
        findViewById<MaterialButton>(R.id.btnQuality).setOnClickListener { engine.applyPreset("quality"); engine.commit(true) }
        findViewById<MaterialButton>(R.id.btnLatency).setOnClickListener { engine.applyPreset("latency"); engine.commit(true) }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { engine.applyPreset("battery"); engine.commit(true) }
        findViewById<MaterialButton>(R.id.btnPreview).setOnClickListener { engine.previewHidden = !engine.previewHidden; engine.commit() }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Novacami", "http://${localIp()}:$port"))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnInfo).setOnClickListener {
            val b = AlertDialog.Builder(this).setTitle("Novacami 1.0")
                .setMessage("By hamzadaoud\nhamzadaoudbn@gmail.com\n\nHardware H.264 encoding, GPU background effects, thermal protection.")
                .setPositiveButton("OK", null)
            if (consent.privacyOptionsRequired)
                b.setNeutralButton("Privacy settings") { _, _ -> consent.showPrivacyOptions { startAdsIfAllowed() } }
            b.show()
        }
        findViewById<MaterialButton>(R.id.btnPin).setOnClickListener { pinDialog() }
        btnStream.setOnClickListener { if (engine.streaming) engine.stop() else startStreaming() }
    }

    /** Ads start only after the user's consent state allows it (worldwide privacy rules). */
    private fun startAdsIfAllowed() {
        if (!consent.canRequestAds || !adsStarted.compareAndSet(false, true)) return
        MobileAds.initialize(this) { }
        adsReady = true
        setupAd()
        refreshUi()
    }

    /** Adaptive AdMob banner at the bottom of the controls column. */
    private fun setupAd() {
        val c = findViewById<FrameLayout>(R.id.adContainer)
        c.post {
            val parentW = (c.parent as View).width
            val wDp = if (parentW > 0) (parentW / resources.displayMetrics.density).toInt() else 320
            val v = AdView(this)
            v.adUnitId = AdConfig.bannerId()
            v.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, wDp))
            c.addView(v)
            v.loadAd(AdRequest.Builder().build())
            adView = v
        }
    }

    override fun onPause() { adView?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); adView?.resume() }

    private fun spinner(sp: Spinner, items: List<String>, onPick: (Int) -> Unit) {
        sp.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = super.getView(position, convertView, parent) as TextView
                t.text = items[position] + "  ▾"
                t.setTextColor(Color.parseColor("#1F2330")); t.textSize = 14f; t.setTypeface(null, Typeface.BOLD)
                return t
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (!uiSync) onPick(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Make the screen match the engine without firing the listeners. */
    private fun refreshUi() {
        uiSync = true
        spBg.setSelection(engine.bg.ordinal, false)
        spNoise.setSelection(engine.noise.ordinal, false)
        spRes.setSelection(if (engine.height >= 1080) 1 else 0, false)
        spFps.setSelection(if (engine.fps >= 60) 1 else 0, false)
        swMirror.isChecked = engine.mirror
        swAuto.isChecked = engine.autoIso
        if (engine.isoMax > engine.isoMin) {
            slider.valueFrom = engine.isoMin.toFloat(); slider.valueTo = engine.isoMax.toFloat()
            slider.value = engine.iso.toFloat().coerceIn(engine.isoMin.toFloat(), engine.isoMax.toFloat())
        }
        slider.isEnabled = !engine.autoIso
        tvIso.text = engine.iso.toString()

        // zoom / exposure / white balance / torch
        val zoomOk = engine.zoomMax > engine.zoomMin + 0.01f
        if (zoomOk) {
            sliderZoom.valueFrom = engine.zoomMin; sliderZoom.valueTo = engine.zoomMax
            sliderZoom.value = engine.zoom.coerceIn(engine.zoomMin, engine.zoomMax)
        }
        sliderZoom.isEnabled = zoomOk
        tvZoom.text = zoomText(engine.zoom)
        btnZoomMin.visibility = if (engine.zoomMin < 0.95f) View.VISIBLE else View.GONE
        btnZoomMin.text = zoomText(engine.zoomMin)
        btnZoom2.isEnabled = engine.zoomMax >= 2f
        val evOk = engine.evMax > engine.evMin
        if (evOk) {
            sliderEv.valueFrom = engine.evMin; sliderEv.valueTo = engine.evMax
            sliderEv.value = engine.ev.coerceIn(engine.evMin, engine.evMax)
        }
        sliderEv.isEnabled = evOk
        tvEv.text = evText(engine.ev)
        spWb.setSelection(engine.wb.ordinal, false)
        swTorch.isEnabled = engine.hasTorch && !engine.front
        swTorch.isChecked = engine.torch && !engine.front
        modePill?.text = if (engine.previewFull) "Fill  ⇄" else "Fit  ⇄"
        resPill.text = "${engine.outW} × ${engine.outH}  |  ${engine.fps} FPS"
        btnStream.text = if (engine.streaming) "■" else "▶"

        // hide preview: GPU stops drawing it and the screen goes dark (big heat/battery saver)
        surface.visibility = if (engine.previewHidden) View.GONE else View.VISIBLE
        previewHidden.visibility = if (engine.previewHidden) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (engine.previewHidden && engine.streaming) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp

        findViewById<View>(R.id.adContainer).visibility =
            if (adsReady && (!engine.streaming || AdConfig.SHOW_WHILE_STREAMING)) View.VISIBLE else View.GONE
        tvPin.text = "PIN  ${engine.pins.pin}"
        btnBrb.text = if (engine.brb) "▶  I'm back" else "☕  Be right back"
        btnBgPic.visibility = if (engine.bg == BgMode.IMAGE) View.VISIBLE else View.GONE
        tvBgNote.visibility = if (engine.guard && engine.bg != BgMode.OFF) View.VISIBLE else View.GONE
        tvTip.text = if (engine.guard) "⚠ Phone is warm - background effect paused to cool down"
                     else "✨ Open Novacami Desktop - it finds your phone, then enter the PIN below"
        uiSync = false
    }

    /** Change the PIN (6-8 digits) or roll a new random one. */
    private fun pinDialog() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(PinStore.MAX_LEN))
            setText(engine.pins.pin); setSelection(text.length)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(et) }
        AlertDialog.Builder(this).setTitle("Stream PIN")
            .setMessage("Needed by Novacami Desktop and OBS to watch or control this phone. ${PinStore.MIN_LEN}-${PinStore.MAX_LEN} digits.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val v = et.text.toString()
                if (v.length in PinStore.MIN_LEN..PinStore.MAX_LEN) { engine.pins.pin = v; refreshUi() }
                else Toast.makeText(this, "PIN must be ${PinStore.MIN_LEN}-${PinStore.MAX_LEN} digits", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Random") { _, _ -> engine.pins.regenerate(); refreshUi() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun zoomText(z: Float) = String.format("%.1f×", z)
    private fun evText(e: Float) = String.format("%+.1f", e)

    private val loop = object : Runnable {
        override fun run() {
            // battery, temperature, thermal guard and fps are measured by the engine (it also runs with the screen off)
            val fps = engine.lastFps
            val mbps = engine.lastMbps
            val s = SpannableString("🎥  FPS: $fps  ●")
            s.setSpan(ForegroundColorSpan(if (fps > 0) Color.parseColor("#22C55E") else Color.GRAY), s.length - 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            fpsPill.text = s
            battery.text = "${engine.battPct}%  🔋  ${engine.battTemp}°C"
            tvUrl.text = "Wi-Fi Stream: http://${localIp()}:$port"
            tvStats.text = String.format("%d fps • %d×%d • %.1f Mbps • H.264 • viewers %d", fps, engine.outW, engine.outH, mbps, engine.server.viewers)
            ui.postDelayed(this, 1000)
        }
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "no network"
    } catch (e: Exception) { "?" }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        adView?.destroy()
        engine.onChanged = null
        engine.onError = null
        // If a stream is running, the service keeps the engine (and the camera) alive. Otherwise release everything.
        if (!engine.streaming) EngineHost.shutdown()
    }

    companion object {
        /** Window.setSustainedPerformanceMode: steadier clocks on phones that support it. Set false to compare. */
        private const val SUSTAINED_MODE = true
    }
}
