package ir.fabsteel.arqc

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** بوق هشدار پیوسته تا وقتی مغایرت برطرف شود */
class Alarm(ctx: Context) {
    private val h = Handler(Looper.getMainLooper())
    private val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var tone: ToneGenerator? = null
    private var running = false
    var muted = false
    var volume = 100
        set(v) { field = v.coerceIn(10, 100); tone?.release(); tone = null }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!muted) {
                try {
                    if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, volume)
                    tone?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 350)
                } catch (e: Exception) { }
            }
            try {
                vib?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) { }
            h.postDelayed(this, 700)
        }
    }

    fun start() { if (running) return; running = true; h.post(tick) }
    fun stop() { running = false; h.removeCallbacks(tick); tone?.release(); tone = null }
}

class MainActivity : AppCompatActivity(), ArRenderer.Listener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var alarm: Alarm
    private var session: Session? = null
    private var installRequested = false
    private var depthOk = false
    private var fixedUi = false
    private var lastResult: QcResult? = null
    private var blink1: ObjectAnimator? = null
    private var blink2: ObjectAnimator? = null
    private var alerting = false
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var toolMode = 0            // 0 هیچ ، 1 برش ، 2 شفافیت ، 3 متر
    private var clipProgress = 100
    private var opacityProgress = 35
    private lateinit var toolPanel: View
    private lateinit var tvToolTitle: TextView
    private lateinit var seekTool: SeekBar
    private lateinit var rowAxis: View
    private lateinit var rowMeasure: View
    private lateinit var tvMeasure: TextView

    private val prefs by lazy { getSharedPreferences("fabsteel", MODE_PRIVATE) }

    private lateinit var tvTolMember: TextView
    private lateinit var tvTolHole: TextView
    private lateinit var tvTrack: TextView
    private lateinit var tvBars: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvFix: TextView
    private lateinit var panelAlert: View
    private lateinit var panelOk: View
    private lateinit var tvAlertDev: TextView
    private lateinit var tvAlertNote: TextView
    private lateinit var tvMatchPct: TextView
    private lateinit var tvMatchTitle: TextView
    private lateinit var progressMatch: ProgressBar
    private lateinit var logoTop: View
    private lateinit var logoMini: View
    private lateinit var loading: View
    private lateinit var loadingText: TextView

    private val pickIfc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadIfc(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(prefs.getInt("night", AppCompatDelegate.MODE_NIGHT_YES))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        alarm = Alarm(this)
        alarm.muted = prefs.getBoolean("muted", false)
        alarm.volume = prefs.getInt("volume", 100)

        glView = findViewById(R.id.glView)
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer = ArRenderer(this, this)
        renderer.tolMemberMm = prefs.getFloat("tolMember", 5f)
        renderer.tolHoleMm = prefs.getFloat("tolHole", 3f)
        renderer.noisePct = prefs.getFloat("noise", 1.0f)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.setWillNotDraw(false)

        tvTolMember = findViewById(R.id.tvTolMember)
        tvTolHole = findViewById(R.id.tvTolHole)
        tvTrack = findViewById(R.id.tvTrack)
        tvBars = findViewById(R.id.tvBars)
        tvHint = findViewById(R.id.tvHint)
        tvFix = findViewById(R.id.tvFix)
        panelAlert = findViewById(R.id.panelAlert)
        panelOk = findViewById(R.id.panelOk)
        tvAlertDev = findViewById(R.id.tvAlertDev)
        tvAlertNote = findViewById(R.id.tvAlertNote)
        tvMatchPct = findViewById(R.id.tvMatchPct)
        tvMatchTitle = findViewById(R.id.tvMatchTitle)
        progressMatch = findViewById(R.id.progressMatch)
        logoTop = findViewById(R.id.logoTop)
        logoMini = findViewById(R.id.logoMini)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        updateTolTexts()

        findViewById<View>(R.id.btnUpload).setOnClickListener { pickIfc.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.cardTolMember).setOnClickListener {
            askNumber("دقت اجزا (بال، جان، دستک، استیفنر) — میلی‌متر", renderer.tolMemberMm) {
                renderer.tolMemberMm = it; prefs.edit().putFloat("tolMember", it).apply(); updateTolTexts()
            }
        }
        findViewById<View>(R.id.cardTolHole).setOnClickListener {
            askNumber("دقت سوراخ‌کاری — میلی‌متر", renderer.tolHoleMm) {
                renderer.tolHoleMm = it; prefs.edit().putFloat("tolHole", it).apply(); updateTolTexts()
            }
        }
        findViewById<View>(R.id.btnFix).setOnClickListener {
            if (ModelStore.model == null) { toast("اول خروجی تکلا (IFC) را آپلود کنید"); return@setOnClickListener }
            renderer.fixRequest = if (fixedUi) 2 else 1
        }
        findViewById<View>(R.id.btnPrev).setOnClickListener { if (!fixedUi) renderer.yawDeg -= 5f }
        findViewById<View>(R.id.btnNext).setOnClickListener { if (!fixedUi) renderer.yawDeg += 5f }
        findViewById<View>(R.id.btnCamera).setOnClickListener { renderer.snapRequest = true }
        findViewById<View>(R.id.btnGallery).setOnClickListener { openGallery() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnReport).setOnClickListener { shareReport() }
        findViewById<View>(R.id.btnHelp).setOnClickListener { showHelp() }

        // قبل از فیکس: کشیدن انگشت روی صفحه، مدل را می‌چرخاند
        glView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = ev.x; downX = ev.x; downY = ev.y }
                MotionEvent.ACTION_MOVE -> {
                    if (!fixedUi && toolMode != 3) renderer.yawDeg += (ev.x - lastX) * 0.2f
                    lastX = ev.x
                }
                MotionEvent.ACTION_UP -> {
                    // در حالت متر، یک ضربه‌ی کوتاه یک نقطه روی سطح می‌گذارد
                    if (toolMode == 3 && kotlin.math.abs(ev.x - downX) < 24f && kotlin.math.abs(ev.y - downY) < 24f) {
                        renderer.tapX = ev.x; renderer.tapY = ev.y
                    }
                }
            }
            true
        }

        setupTools()
        ModelStore.model?.let { renderer.setModel(it) }
        refreshHint()
    }

    // ---------------- ابزارها: برش ، شفافیت ، متر ----------------
    private fun setupTools() {
        toolPanel = findViewById(R.id.toolPanel)
        tvToolTitle = findViewById(R.id.tvToolTitle)
        seekTool = findViewById(R.id.seekTool)
        rowAxis = findViewById(R.id.rowAxis)
        rowMeasure = findViewById(R.id.rowMeasure)
        tvMeasure = findViewById(R.id.tvMeasure)
        renderer.fillAlpha = 0.05f + 0.75f * opacityProgress / 100f

        findViewById<View>(R.id.btnToolClip).setOnClickListener { setTool(1) }
        findViewById<View>(R.id.btnToolOpacity).setOnClickListener { setTool(2) }
        findViewById<View>(R.id.btnToolMeasure).setOnClickListener { setTool(3) }
        findViewById<View>(R.id.btnToolClose).setOnClickListener { setTool(0) }
        findViewById<View>(R.id.btnMeasureClear).setOnClickListener { renderer.clearMeasure() }
        findViewById<View>(R.id.btnAxX).setOnClickListener { renderer.clipAxis = 0; updateToolTitle() }
        findViewById<View>(R.id.btnAxY).setOnClickListener { renderer.clipAxis = 1; updateToolTitle() }
        findViewById<View>(R.id.btnAxZ).setOnClickListener { renderer.clipAxis = 2; updateToolTitle() }
        findViewById<View>(R.id.btnFlip).setOnClickListener { renderer.clipFlip = !renderer.clipFlip; updateToolTitle() }

        seekTool.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (toolMode) {
                    1 -> { clipProgress = p; renderer.clipPos = p / 100f; renderer.clipOn = p < 100 }
                    2 -> { opacityProgress = p; renderer.fillAlpha = 0.05f + 0.75f * p / 100f }
                }
                updateToolTitle()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setTool(m: Int) {
        toolMode = if (toolMode == m) 0 else m
        toolPanel.visibility = if (toolMode == 0) View.GONE else View.VISIBLE
        rowAxis.visibility = if (toolMode == 1) View.VISIBLE else View.GONE
        rowMeasure.visibility = if (toolMode == 3) View.VISIBLE else View.GONE
        seekTool.visibility = if (toolMode == 1 || toolMode == 2) View.VISIBLE else View.GONE
        when (toolMode) {
            1 -> seekTool.progress = clipProgress
            2 -> seekTool.progress = opacityProgress
        }
        updateToolTitle()
    }

    private fun updateToolTitle() {
        tvToolTitle.text = when (toolMode) {
            1 -> {
                val ax = when (renderer.clipAxis) { 0 -> "X"; 1 -> "Y"; else -> "Z" }
                val dir = if (renderer.clipFlip) "−" else "+"
                if (clipProgress >= 100) "برش (خاموش) — محور $ax$dir — لغزنده را بکشید"
                else "برش فعال — محور $ax$dir — ${clipProgress}%"
            }
            2 -> "شفافیت سطوح مدل — ${100 - opacityProgress}%"
            3 -> "متر: دو نقطه روی سطح را لمس کنید"
            else -> ""
        }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------- چرخه‌ی ARCore ----------------
    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                    else -> {}
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
                    return
                }
                val s = Session(this)
                val cfg = Config(s)
                cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                cfg.focusMode = Config.FocusMode.AUTO
                cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                depthOk = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                if (depthOk) cfg.depthMode = Config.DepthMode.AUTOMATIC
                s.configure(cfg)
                session = s
                renderer.depthEnabled = depthOk
            } catch (e: Exception) {
                toast("ARCore در دسترس نیست: ${e.javaClass.simpleName}. «Google Play Services for AR» را نصب/به‌روز کنید.")
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            toast("دوربین در دسترس نیست؛ برنامه‌های دیگر را ببندید.")
            session = null
            return
        }
        renderer.session = session
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        alarm.stop()
        stopBlink()
        alerting = false
        renderer.session = null
        glView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close(); session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) onResume()
        else toast("بدون اجازه‌ی دوربین، برنامه کار نمی‌کند")
    }

    // ---------------- IFC ----------------
    private fun loadIfc(uri: android.net.Uri) {
        loading.visibility = View.VISIBLE
        loadingText.text = "در حال خواندن فایل…"
        Thread {
            try {
                val ins = contentResolver.openInputStream(uri) ?: throw IllegalStateException("فایل باز نشد")
                val m = ins.use { IfcParser { msg -> runOnUiThread { loadingText.text = msg } }.parse(it) }
                ModelStore.model = m
                runOnUiThread {
                    renderer.setModel(m)
                    loading.visibility = View.GONE
                    val sz = m.sizeM
                    val big = if (m.members.size > 3000) "\nاین فایل کل پروژه است؛ برای QC روی قطعه، فقط همان اسمبلی/قطعه را از تکلا خروجی بگیرید." else ""
                    toast("${m.members.size} قطعه — ابعاد ${(sz[0] * 1000).toInt()}×${(sz[2] * 1000).toInt()}×${(sz[1] * 1000).toInt()} mm$big")
                    refreshHint()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    loading.visibility = View.GONE
                    toast("خطا در خواندن IFC: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun refreshHint() {
        tvHint.text = when {
            ModelStore.model == null -> "خروجی IFC تکلا را آپلود کنید"
            !fixedUi -> "علامت + را روی نقطه‌ی مرجع قطعه (گوشه‌ی پایین-چپ مدل) بگذارید، انگشت را بکشید تا بچرخد، سپس Fix"
            else -> "مدل روی قطعه قفل شد"
        }
    }

    // ---------------- ArRenderer.Listener ----------------
    override fun onStatus(tracking: Boolean, quality: Int, depthOk: Boolean, hasHit: Boolean) {
        runOnUiThread {
            val word = when { quality > 75 -> "Perfect"; quality > 45 -> "Good"; else -> "Poor" }
            tvTrack.text = if (tracking) "Tracking… $quality% · $word" else "Tracking lost"
            tvBars.text = when {
                !tracking -> "▁▁▁▁"
                quality > 75 -> "▂▄▆█"
                quality > 45 -> "▂▄▆"
                quality > 20 -> "▂▄"
                else -> "▂"
            }
            tvTrack.setTextColor(ContextCompat.getColor(this, if (tracking) R.color.ok_green else R.color.alert_red))
            tvBars.setTextColor(ContextCompat.getColor(this, if (tracking) R.color.ok_green else R.color.alert_red))
            if (!depthOk && fixedUi) tvMatchTitle.text = "این گوشی عمق ARCore ندارد"
        }
    }

    override fun onFixed(fixed: Boolean) {
        runOnUiThread {
            fixedUi = fixed
            tvFix.text = if (fixed) "Unfix" else "Fix"
            if (!fixed) { setAlert(false, 0f, ""); tvMatchPct.text = "—"; progressMatch.progress = 0 }
            refreshHint()
        }
    }

    override fun onQc(r: QcResult) {
        lastResult = r
        runOnUiThread {
            tvMatchTitle.text = "همخوانی مدل و قطعه"
            tvMatchPct.text = if (r.evaluated == 0) "—" else "${r.percent}%"
            progressMatch.progress = r.percent
            val alert = r.mismatch > 0
            setAlert(alert, r.worstDevMm, r.worstName)
            tvAlertNote.visibility = if (r.noiseLimited) View.VISIBLE else View.GONE
        }
    }

    private fun setAlert(on: Boolean, dev: Float, name: String) {
        panelAlert.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            tvAlertDev.text = "%.1f mm".format(Locale.US, dev)
            if (!alerting) { alerting = true; startBlink(); alarm.start() }
        } else if (alerting) {
            alerting = false; stopBlink(); alarm.stop()
        }
    }

    private fun startBlink() {
        fun mk(v: View) = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.12f).apply {
            duration = 350; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
        blink1 = mk(logoTop); blink2 = mk(logoMini)
    }

    private fun stopBlink() {
        blink1?.cancel(); blink2?.cancel(); blink1 = null; blink2 = null
        logoTop.alpha = 1f; logoMini.alpha = 1f
    }

    override fun onMeasure(distMm: Float, count: Int) {
        runOnUiThread {
            tvMeasure.text = when {
                distMm >= 0f -> "%.0f mm".format(Locale.US, distMm)
                distMm == -2f -> "سطحی پیدا نشد؛ جای دیگر لمس کنید"
                count == 1 -> "نقطه‌ی دوم را لمس کنید"
                else -> "دو نقطه را لمس کنید"
            }
        }
    }

    override fun onSnapshot(bmp: Bitmap) {
        runOnUiThread {
            try {
                val name = "FabSteelQC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.insertImage(contentResolver, bmp, name, "FAB STEEL AR QC")
                toast("عکس QC در گالری ذخیره شد")
            } catch (e: Exception) { toast("ذخیره‌ی عکس ناموفق بود") }
        }
    }

    // ---------------- دیالوگ‌ها ----------------
    private fun updateTolTexts() {
        tvTolMember.text = fmt(renderer.tolMemberMm) + " mm"
        tvTolHole.text = fmt(renderer.tolHoleMm) + " mm"
    }

    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(Locale.US, v)

    private fun askNumber(title: String, cur: Float, onOk: (Float) -> Unit) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.setText(fmt(cur)); et.textSize = 26f; et.setSelectAllOnFocus(true)
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("تأیید") { _, _ ->
                val v = et.text.toString().replace('٫', '.').toFloatOrNull()
                if (v != null && v >= 0f) onOk(v) else toast("عدد معتبر وارد کنید")
            }.setNegativeButton("لغو", null).show()
    }

    private fun showSettings() {
        val items = arrayOf("تم برنامه (روشن / تاریک)", "صدای هشدار: " + if (alarm.muted) "خاموش" else "روشن",
            "حجم صدا", "حد نویز حسگر عمق (٪ فاصله): " + fmt(renderer.noisePct), "درباره")
        AlertDialog.Builder(this).setTitle("تنظیمات").setItems(items) { _, i ->
            when (i) {
                0 -> AlertDialog.Builder(this).setTitle("تم")
                    .setItems(arrayOf("روشن", "تاریک", "مطابق سیستم")) { _, w ->
                        val mode = when (w) { 0 -> AppCompatDelegate.MODE_NIGHT_NO; 1 -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
                        prefs.edit().putInt("night", mode).apply()
                        AppCompatDelegate.setDefaultNightMode(mode)
                    }.show()
                1 -> { alarm.muted = !alarm.muted; prefs.edit().putBoolean("muted", alarm.muted).apply()
                    toast("صدای هشدار " + if (alarm.muted) "خاموش شد" else "روشن شد") }
                2 -> {
                    val sb = SeekBar(this); sb.max = 100; sb.progress = alarm.volume
                    sb.setPadding(48, 48, 48, 48)
                    AlertDialog.Builder(this).setTitle("حجم صدای هشدار").setView(sb)
                        .setPositiveButton("تأیید") { _, _ ->
                            alarm.volume = sb.progress; prefs.edit().putInt("volume", alarm.volume).apply()
                        }.show()
                }
                3 -> askNumber("حد نویز حسگر عمق (٪ فاصله)", renderer.noisePct) {
                    renderer.noisePct = it; prefs.edit().putFloat("noise", it).apply() }
                4 -> AlertDialog.Builder(this).setTitle("FAB STEEL AR QC")
                    .setMessage("نسخه ۱۰\nبرنامه‌نویس: ENG DAVOD BAGHERI\n\nتمام پردازش روی خود گوشی انجام می‌شود.").show()
            }
        }.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("راهنما").setMessage(
            "۱) «Tekla Upload IFC» را بزنید و خروجی IFC تکلا را انتخاب کنید.\n" +
            "۲) دوربین را به قطعه بگیرید تا Tracking سبز شود.\n" +
            "۳) علامت + وسط صفحه را روی نقطه‌ی مرجع قطعه بگذارید (گوشه‌ی پایین-چپ مدل). " +
            "با کشیدن انگشت یا دکمه‌های « » مدل را بچرخانید.\n" +
            "۴) «Fix» را بزنید تا مدل قفل شود.\n" +
            "۵) اگر اختلاف از دقت‌های تعریف‌شده بیشتر شود، قطعه قرمز می‌شود، لوگو چشمک می‌زند و بوق می‌خورد.\n\n" +
            "دقت اجزا و دقت سوراخ‌کاری را با لمس کارت‌های سمت چپ تغییر دهید."
        ).setPositiveButton("باشه", null).show()
    }

    private fun shareReport() {
        val r = lastResult
        if (r == null || r.rows.isEmpty()) { toast("هنوز نتیجه‌ی QC وجود ندارد (اول Fix بزنید)"); return }
        val sb = StringBuilder()
        sb.append("FAB STEEL AR QC\n")
        sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())).append('\n')
        sb.append("Members tol: ${fmt(renderer.tolMemberMm)} mm | Holes tol: ${fmt(renderer.tolHoleMm)} mm\n")
        sb.append("Match: ${r.percent}% (${r.okCount}/${r.evaluated})\n\n")
        sb.append("name,type,dev_mm,tol_mm,status\n")
        for (row in r.rows.sortedByDescending { it.devMm })
            sb.append("${row.name},${if (row.isHole) "hole" else "member"},${"%.1f".format(Locale.US, row.devMm)},${"%.1f".format(Locale.US, row.tolMm)},${if (row.ok) "OK" else "MISMATCH"}\n")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "FAB STEEL QC report"); putExtra(Intent.EXTRA_TEXT, sb.toString())
        }, "ارسال گزارش"))
    }

    private fun openGallery() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        } catch (e: Exception) { toast("برنامه‌ی گالری پیدا نشد") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
