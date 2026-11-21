package com.screenrec.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File

/** Tampil otomatis setelah rekaman selesai: pratinjau video + Bagikan, Edit, Simpan. */
class PreviewActivity : Activity() {

    private lateinit var file: File
    private var savedUri: Uri? = null
    private lateinit var saveBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var video: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val path = intent.getStringExtra(EXTRA_FILE)
        if (path == null || !File(path).exists()) {
            finish()
            return
        }
        file = File(path)
        savedUri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        setContentView(buildUi())
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    /** Uri aman (FileProvider) yang bisa dibaca aplikasi lain. */
    private fun shareUri(): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(18), dp(24), dp(18), dp(20))
        }

        // Judul
        root.addView(Ui.text(this, "Rekaman selesai", 22f, Ui.TEXT, true))
        statusText = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(14)) }
        root.addView(statusText)

        // Video
        video = VideoView(this)
        val frame = FrameLayout(this).apply {
            background = Ui.rounded(this@PreviewActivity, 0xFF000000.toInt(), 20, Ui.BORDER, 1)
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        frame.addView(video, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val mc = MediaController(this)
        mc.setAnchorView(frame)
        video.setMediaController(mc)
        video.setVideoURI(shareUri())
        video.setOnPreparedListener { it.isLooping = false; video.start() }

        // Info file
        root.addView(Ui.text(this, file.name, 13f, Ui.SUBTLE).apply { setPadding(dp(2), dp(14), 0, 0) })
        root.addView(Ui.text(this, infoLine(), 12f, Ui.MUTED).apply { setPadding(dp(2), dp(2), 0, dp(14)) })

        // Tombol aksi di bawah video
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            actionButton("Bagikan", Ui.ACCENT, Ui.ACCENT2) { share() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        )
        row.addView(
            actionButton("Edit", 0xFF334155.toInt(), 0xFF475569.toInt()) { edit() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        )
        saveBtn = actionButton("Simpan", Ui.OK, 0xFF059669.toInt()) { save() }
        row.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        // Tombol sekunder
        val sec = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        sec.addView(textLink("Buang rekaman", Ui.DANGER) { discard() })
        sec.addView(View(this), LinearLayout.LayoutParams(dp(24), 1))
        sec.addView(textLink("Tutup", Ui.MUTED) { finish() })
        root.addView(sec, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        refreshSaveState()
        return root
    }

    private fun actionButton(label: String, c1: Int, c2: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(15), 0, dp(15))
        background = Ui.ripple(this@PreviewActivity, Ui.gradient(this@PreviewActivity, c1, c2, 14), 14)
        setOnClickListener { onClick() }
    }

    private fun textLink(label: String, color: Int, onClick: () -> Unit) = Ui.text(this, label, 14f, color).apply {
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { onClick() }
    }

    private fun infoLine(): String {
        val mb = file.length() / (1024.0 * 1024.0)
        var dur = ""
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            val s = ms / 1000
            dur = "%02d:%02d  •  ".format(s / 60, s % 60)
        } catch (_: Exception) {
        }
        return dur + "%.1f MB".format(mb)
    }

    private fun refreshSaveState() {
        if (savedUri != null) {
            saveBtn.text = "Tersimpan ✓"
            saveBtn.alpha = 0.6f
            saveBtn.isEnabled = false
            statusText.text = "Tersimpan di Movies/ScreenRec"
        } else {
            saveBtn.text = "Simpan"
            saveBtn.alpha = 1f
            saveBtn.isEnabled = true
            statusText.text = "Belum disimpan ke galeri"
        }
    }

    // ------------------------------------------------------------- aksi

    private fun share() {
        val uri = shareUri()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Bagikan rekaman"))
    }

    private fun edit() {
        val uri = shareUri()
        val edit = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, "video/mp4")
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(edit, "Edit dengan"))
        } catch (e: ActivityNotFoundException) {
            statusText.text = "Belum ada aplikasi editor video di HP ini"
        }
    }

    private fun save() {
        if (savedUri != null) return
        val uri = MediaSaver.save(this, file)
        if (uri != null) {
            savedUri = uri
            refreshSaveState()
        } else {
            statusText.text = "Gagal menyimpan rekaman"
        }
    }

    private fun discard() {
        try {
            savedUri?.let { contentResolver.delete(it, null, null) }
        } catch (_: Exception) {
        }
        file.delete()
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized && video.isPlaying) video.pause()
    }

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_URI = "uri"
    }
}
