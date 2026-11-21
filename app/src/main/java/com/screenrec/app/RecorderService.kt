package com.screenrec.app

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Service latar depan: memegang MediaProjection, menampilkan tombol melayang,
 * memilih area, hitung mundur, merekam, dan menghentikan rekaman lewat goyangan HP.
 */
class RecorderService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var sensors: SensorManager
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null

    private var bubble: ImageView? = null
    private var selector: RegionSelectorView? = null
    private var menu: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var recDot: View? = null
    private var recDotAnim: ObjectAnimator? = null

    private var state = STATE_IDLE
    private var recordStartedAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Dipicu bila pengguna mencabut izin rekam layar dari status bar
            if (projection != null) quit()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        prefs = Prefs(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Perekam layar", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> init(intent)
            ACTION_TOGGLE -> if (projection != null) toggle() else stopSelf()
            ACTION_QUIT -> quit()
            else -> if (projection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ init

    @Suppress("DEPRECATION")
    private fun init(intent: Intent) {
        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        if (data == null) {
            quit()
            return
        }
        val old = projection
        projection = null
        old?.stop()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(projectionCallback, main)
        projection = p
        active = true
        state = STATE_IDLE
        showBubble()
    }

    // ---------------------------------------------------------------- helper

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
    }

    private fun removeView(v: View?) {
        if (v == null) return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val size = dp(56)
        val (sw, sh) = realSize()
        val lp = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - size - dp(8)          // sisi kanan
            y = (sh * 0.65f).toInt()       // agak ke bawah
        }
        bubbleLp = lp
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val v = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageResource(R.drawable.bubble_idle)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { view: View, e: MotionEvent ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(view, lp)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTap()
                    true
                }

                else -> false
            }
        }
        wm.addView(v, lp)
        bubble = v
    }

    private fun removeBubble() {
        removeView(bubble)
        bubble = null
        bubbleLp = null
    }

    // -------------------------------------------------------------- alur

    private fun toggle() {
        when (state) {
            STATE_RECORDING -> stopRecording()
            STATE_IDLE -> startFlow()
            else -> {}
        }
    }

    /** Ketuk tombol melayang: buka menu melingkar di posisi tombol. */
    private fun onBubbleTap() {
        if (state != STATE_IDLE) return
        val lp = bubbleLp
        val size = dp(56)
        val cx = (lp?.x ?: 24) + size / 2f
        val cy = (lp?.y ?: 320) + size / 2f
        openRadial(cx, cy)
    }

    /** Mulai: langsung hitung mundur (layar penuh) atau pilih area dulu. */
    private fun startFlow() {
        if (state != STATE_IDLE) return
        if (prefs.aspect == Prefs.ASPECT_FULL) beginRecording(null) else showSelector()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun openPreview(res: RecordingResult) {
        startActivity(
            Intent(this, PreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(PreviewActivity.EXTRA_FILE, res.file.absolutePath)
                .putExtra(PreviewActivity.EXTRA_URI, res.savedUri?.toString())
        )
    }

    // --------------------------------------------------- pemilih area rekam

    private fun showSelector() {
        state = STATE_SELECTING
        removeBubble()
        val (w, h) = realSize()
        val v = RegionSelectorView(
            this,
            Prefs.ratioFor(prefs.aspect),
            onStart = { rect ->
                removeSelector()
                beginRecording(rect)
            },
            onCancel = {
                removeSelector()
                state = STATE_IDLE
                showBubble()
            }
        )
        val lp = overlayParams(w, h).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        selector = v
    }

    private fun removeSelector() {
        removeView(selector)
        selector = null
    }

    // ------------------------------------------------------ mulai merekam

    private fun beginRecording(region: Rect?) {
        state = STATE_COUNTDOWN   // status "bersiap", tanpa hitungan mundur
        removeBubble()
        // jeda singkat agar pemilih area / menu benar-benar hilang dari layar sebelum direkam
        main.postDelayed({ startRecording(region) }, 350)
    }

    // ------------------------------------------------------------ merekam

    private fun startRecording(region: Rect?) {
        val p = projection
        if (p == null || state != STATE_COUNTDOWN) return
        val rec = ScreenRecorder(this, p, prefs, region)
        if (rec.start()) {
            recorder = rec
            state = STATE_RECORDING
            recordStartedAt = SystemClock.elapsedRealtime()
            showRecDot(region)
            registerShake()
            refreshNotification()
        } else {
            state = STATE_IDLE
            showBubble()
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        if (state != STATE_RECORDING) return
        state = STATE_STOPPING
        unregisterShake()
        removeMenu()
        removeRecDot()
        Thread {
            val res = rec.stop()
            main.post {
                recorder = null
                state = STATE_IDLE
                refreshNotification()
                showBubble()
                if (res != null) openPreview(res)
            }
        }.start()
    }

    private fun quit() {
        unregisterShake()
        removeMenu()
        removeRecDot()
        removeSelector()
        removeBubble()

        val rec = recorder
        if (rec != null && state == STATE_RECORDING) {
            state = STATE_STOPPING
            Thread {
                val res = rec.stop()
                main.post {
                    recorder = null
                    if (res != null) openPreview(res)
                    finishService()
                }
            }.start()
        } else {
            finishService()
        }
    }

    private fun finishService() {
        state = STATE_IDLE
        active = false
        main.removeCallbacksAndMessages(null)
        removeBubble()
        val p = projection
        projection = null
        p?.unregisterCallback(projectionCallback)
        p?.stop()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        active = false
        unregisterShake()
        removeMenu()
        removeRecDot()
        removeSelector()
        removeBubble()
        super.onDestroy()
    }

    // --------------------------------------------------------------- goyang

    private var shakeCount = 0
    private var shakeWindowStart = 0L

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (state != STATE_RECORDING || menu != null) return
            val now = SystemClock.elapsedRealtime()
            if (now - recordStartedAt < 1500) return   // abaikan getaran saat mulai
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
                SensorManager.GRAVITY_EARTH
            if (g > SHAKE_G) {
                if (now - shakeWindowStart > 900) {
                    shakeWindowStart = now
                    shakeCount = 0
                }
                shakeCount++
                if (shakeCount >= 3) {
                    shakeCount = 0
                    stopRecording()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun registerShake() {
        shakeCount = 0
        shakeWindowStart = 0
        val acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors.registerListener(shakeListener, acc, SensorManager.SENSOR_DELAY_GAME, main)
    }

    private fun unregisterShake() {
        try {
            sensors.unregisterListener(shakeListener)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------ titik merah berkedip

    /**
     * Titik merah kecil berkedip di kanan atas. Semua yang tampil di layar ikut terekam,
     * jadi titik hanya ditampilkan bila posisinya DI LUAR area rekam (mode 16:9, 4:3, custom).
     * Pada mode layar penuh titik tidak ditampilkan.
     */
    private fun showRecDot(region: Rect?) {
        if (recDot != null || region == null) return
        val (sw, _) = realSize()
        val size = dp(14)
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (sbId > 0) resources.getDimensionPixelSize(sbId) else dp(24)
        val dotX = sw - size - dp(12)
        val dotY = statusBar + dp(6)

        // beri jarak aman 8dp di sekeliling titik
        val pad = dp(8)
        val dotArea = Rect(dotX - pad, dotY - pad, dotX + size + pad, dotY + size + pad)
        if (Rect.intersects(region, dotArea)) return

        val v = View(this).apply {
            background = Ui.rounded(this@RecorderService, Ui.DANGER, 7)
        }
        val lp = overlayParams(size, size, touchable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dotX
            y = dotY
        }
        wm.addView(v, lp)
        recDot = v
        recDotAnim = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.15f).apply {
            duration = 700
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun removeRecDot() {
        recDotAnim?.cancel()
        recDotAnim = null
        removeView(recDot)
        recDot = null
    }

    // -------------------------------------------------------- menu melingkar

    private fun openRadial(cx: Float, cy: Float) {
        if (menu != null) return
        val recording = state == STATE_RECORDING
        if (!recording && state != STATE_IDLE) return
        val (sw, sh) = realSize()
        val items = listOf(
            RadialItem("Mulai", RadialItem.PLAY, !recording) { startFlow() },
            RadialItem("Berhenti", RadialItem.STOP, recording) { stopRecording() },
            RadialItem("Pengaturan", RadialItem.SETTINGS, true) { openSettings() },
        )
        lateinit var radial: RadialMenu
        radial = RadialMenu(this, cx, cy, sw, sh, items) {
            // dipanggil setelah animasi tutup selesai (pilihan dipilih atau dibatalkan)
            removeView(radial)
            if (menu === radial) menu = null
        }
        wm.addView(radial, overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menu = radial
    }

    private fun removeMenu() {
        removeView(menu)
        menu = null
    }

    // ---------------------------------------------------------- notification

    private fun refreshNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val recording = state == STATE_RECORDING || state == STATE_STOPPING
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code,
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val icon = Icon.createWithResource(this, R.drawable.ic_notif)
        val toggle = Notification.Action.Builder(
            icon, if (recording) "Berhenti" else "Mulai rekam", pi(ACTION_TOGGLE, 1)
        ).build()
        val quit = Notification.Action.Builder(icon, "Tutup", pi(ACTION_QUIT, 2)).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Perekam Layar")
            .setContentText(
                if (recording) "Sedang merekam. Goyangkan HP untuk berhenti."
                else "Siap merekam. Ketuk tombol melayang."
            )
            .setOngoing(true)
            .addAction(toggle)
            .addAction(quit)
            .build()
    }

    companion object {
        const val ACTION_INIT = "com.screenrec.app.INIT"
        const val ACTION_TOGGLE = "com.screenrec.app.TOGGLE"
        const val ACTION_QUIT = "com.screenrec.app.QUIT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** True selama tombol melayang aktif (dipakai MainActivity untuk menampilkan status). */
        @Volatile
        var active = false

        private const val CHANNEL_ID = "rec"
        private const val NOTIF_ID = 1
        private const val SHAKE_G = 2.4f

        private const val STATE_IDLE = 0
        private const val STATE_SELECTING = 1
        private const val STATE_COUNTDOWN = 2
        private const val STATE_RECORDING = 3
        private const val STATE_STOPPING = 4
    }
}
