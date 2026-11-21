package com.screenrec.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var mainBtn: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var aspectHint: TextView
    private lateinit var autosaveHint: TextView
    private lateinit var hintText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        prefs = Prefs(this)
        setContentView(buildUi())
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    // ------------------------------------------------------------ komponen UI

    private fun card(title: String, subtitle: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.SURFACE, 20, Ui.BORDER, 1)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        c.addView(Ui.text(this, title, 16f, Ui.TEXT, true))
        if (subtitle != null) {
            c.addView(Ui.text(this, subtitle, 12f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        }
        c.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
        return c
    }

    private fun caption(text: String) =
        Ui.text(this, text, 12f, Ui.MUTED).apply { setPadding(dp(2), dp(14), 0, dp(6)) }

    /** Baris chip berbentuk pil, satu terpilih. */
    private fun chipRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = mutableListOf<TextView>()

        fun style(chip: TextView, on: Boolean) {
            chip.background = Ui.ripple(
                this,
                if (on) Ui.gradient(this, Ui.ACCENT, Ui.ACCENT2, 12)
                else Ui.rounded(this, Ui.CHIP, 12),
                12
            )
            chip.setTextColor(if (on) Color.WHITE else Ui.SUBTLE)
        }

        labels.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener {
                    chips.forEachIndexed { j, c -> style(c, j == i) }
                    onPick(i)
                }
            }
            style(chip, i == selected)
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i < labels.size - 1) dp(6) else 0
            })
        }
        return row
    }

    private fun autosaveHintText(on: Boolean) =
        if (on) "Rekaman langsung masuk ke galeri (Movies/ScreenRec)."
        else "Rekaman baru disimpan setelah Anda menekan Simpan di layar hasil."

    private fun aspectHintText(i: Int) = when (i) {
        0 -> "Seluruh layar direkam."
        1, 2 -> "Saat mulai, geser dan ubah ukuran kotak ${Prefs.ASPECT_LABELS[i]}, lalu tekan Mulai."
        else -> "Saat mulai, tarik sudut kotak untuk menentukan area bebas, lalu tekan Mulai."
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "●"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ui.gradient(this@MainActivity, Ui.DANGER, Ui.DANGER2, 14)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(Ui.text(this, "Perekam Layar", 22f, Ui.TEXT, true))
        if (version.isNotEmpty()) {
            titleRow.addView(Ui.text(this, "v$version", 11f, Color.WHITE, true).apply {
                background = Ui.gradient(this@MainActivity, Ui.ACCENT, Ui.ACCENT2, 8)
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) })
        }
        titles.addView(titleRow)
        titles.addView(Ui.text(this, "Rekam layar + suara internal", 12f, Ui.MUTED))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.SURFACE, 20, Ui.BORDER, 1)
            setPadding(dp(10), dp(6), dp(12), dp(6))
        }
        statusDot = View(this)
        pill.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        statusText = Ui.text(this, "Nonaktif", 12f, Ui.SUBTLE)
        pill.addView(statusText)
        header.addView(pill)
        root.addView(header)

        // Kartu area rekam
        val areaCard = card("Area rekam", "Pilih bagian layar yang direkam")
        areaCard.addView(caption("Bentuk area"))
        areaCard.addView(chipRow(Prefs.ASPECT_LABELS, prefs.aspect) {
            prefs.aspect = it
            aspectHint.text = aspectHintText(it)
        })
        aspectHint = Ui.text(this, aspectHintText(prefs.aspect), 12f, Ui.MUTED).apply {
            setPadding(dp(2), dp(10), 0, 0)
        }
        areaCard.addView(aspectHint)
        root.addView(areaCard)

        // Kartu kualitas
        val qCard = card("Kualitas video", "Semakin tinggi, semakin besar ukuran file")
        qCard.addView(caption("Resolusi"))
        qCard.addView(chipRow(Prefs.RESOLUTION_LABELS, Prefs.RESOLUTIONS.indexOf(prefs.resolutionHeight).coerceAtLeast(1)) {
            prefs.resolutionHeight = Prefs.RESOLUTIONS[it]
        })
        qCard.addView(caption("Frame per detik"))
        qCard.addView(chipRow(Prefs.FPS_OPTIONS.map { "$it FPS" }, Prefs.FPS_OPTIONS.indexOf(prefs.fps).coerceAtLeast(1)) {
            prefs.fps = Prefs.FPS_OPTIONS[it]
        })
        qCard.addView(caption("Bitrate (Mbps)"))
        qCard.addView(chipRow(Prefs.BITRATE_OPTIONS.map { "$it" }, Prefs.BITRATE_OPTIONS.indexOf(prefs.bitrateMbps).coerceAtLeast(0)) {
            prefs.bitrateMbps = Prefs.BITRATE_OPTIONS[it]
        })
        root.addView(qCard)

        // Kartu penyimpanan
        val sCard = card("Penyimpanan")
        val saveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val saveTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        saveTexts.addView(Ui.text(this, "Simpan otomatis", 14f, Ui.TEXT, true))
        autosaveHint = Ui.text(this, autosaveHintText(prefs.autosave), 12f, Ui.MUTED).apply {
            setPadding(0, dp(2), 0, 0)
        }
        saveTexts.addView(autosaveHint)
        saveRow.addView(saveTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveRow.addView(
            Ui.toggle(this, prefs.autosave) {
                prefs.autosave = it
                autosaveHint.text = autosaveHintText(it)
            },
            LinearLayout.LayoutParams(dp(48), dp(28))
        )
        sCard.addView(saveRow)
        root.addView(sCard)

        // Tombol utama
        mainBtn = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { onMainButton() }
        }
        root.addView(mainBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        hintText = Ui.text(this, "", 12f, Ui.DANGER).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), 0)
            visibility = View.GONE
        }
        root.addView(hintText)

        // Cara pakai
        val tips = card("Cara pakai")
        listOf(
            "1.  Aktifkan tombol melayang dan izinkan semua permintaan.",
            "2.  Buka aplikasi yang mau direkam, ketuk bola merah, lalu pilih Mulai.",
            "3.  Rekaman langsung jalan dan tombol hilang.",
            "4.  Goyangkan HP untuk berhenti. Hasil langsung tampil.",
            "5.  Pilih Simpan, Bagikan, atau Edit."
        ).forEach {
            tips.addView(Ui.text(this, it, 13f, Ui.SUBTLE).apply { setPadding(0, dp(8), 0, 0) })
        }
        tips.addView(Ui.text(
            this,
            "Catatan: suara aplikasi yang memblokir perekaman akan hening, dan panggilan telepon tidak bisa direkam.",
            12f, Ui.MUTED
        ).apply { setPadding(0, dp(14), 0, 0) })
        root.addView(tips)

        return ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        }
    }

    private fun showHint(msg: String) {
        hintText.text = msg
        hintText.visibility = View.VISIBLE
    }

    private fun refreshState() {
        val on = RecorderService.active
        statusDot.background = Ui.rounded(this, if (on) Ui.OK else Ui.MUTED, 4)
        statusText.text = if (on) "Aktif" else "Nonaktif"
        mainBtn.text = if (on) "Matikan tombol melayang" else "Aktifkan tombol melayang"
        mainBtn.background = Ui.ripple(
            this,
            if (on) Ui.gradient(this, Ui.DANGER, Ui.DANGER2, 16)
            else Ui.gradient(this, Ui.ACCENT, Ui.ACCENT2, 16),
            16
        )
    }

    private fun onMainButton() {
        if (RecorderService.active) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_QUIT))
            RecorderService.active = false
            refreshState()
        } else {
            startFlow()
        }
    }

    // ------------------------------------------------------------- izin

    // Langkah 1: izin mikrofon (wajib untuk perekaman suara internal)
    private fun startFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        askOverlay()
    }

    // Langkah 2: izin tampil di atas aplikasi lain
    private fun askOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            showHint("Aktifkan \"Tampil di atas aplikasi lain\" untuk Perekam Layar, lalu kembali ke sini.")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        askProjection()
    }

    // Langkah 3: izin rekam layar
    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_AUDIO) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) askOverlay()
            else showHint("Izin mikrofon diperlukan untuk merekam suara internal.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> if (Settings.canDrawOverlays(this)) askProjection()
            else showHint("Izin tampil di atas aplikasi lain belum aktif.")

            REQ_PROJECTION -> if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_INIT)
                    .putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(RecorderService.EXTRA_RESULT_DATA, data)
                startForegroundService(svc)
                moveTaskToBack(true)
            }
        }
    }

    companion object {
        private const val REQ_AUDIO = 1
        private const val REQ_OVERLAY = 2
        private const val REQ_PROJECTION = 3
    }
}
