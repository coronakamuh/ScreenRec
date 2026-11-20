package com.screenrec.app

import android.content.Context

/** Pengaturan rekaman yang disimpan di SharedPreferences. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("screenrec", Context.MODE_PRIVATE)

    /** Tinggi sisi pendek video dalam piksel. 0 = ukuran asli. */
    var resolutionHeight: Int
        get() = sp.getInt("res", 720)
        set(v) = sp.edit().putInt("res", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()

    /** Bitrate video dalam Mbps. */
    var bitrateMbps: Int
        get() = sp.getInt("bitrate", 6)
        set(v) = sp.edit().putInt("bitrate", v).apply()

    /** Indeks ke ASPECT_LABELS: 0 penuh, 1 = 16:9, 2 = 4:3, 3 = custom. */
    var aspect: Int
        get() = sp.getInt("aspect", 0)
        set(v) = sp.edit().putInt("aspect", v).apply()

    /** Simpan otomatis ke galeri setelah rekaman selesai. */
    var autosave: Boolean
        get() = sp.getBoolean("autosave", true)
        set(v) = sp.edit().putBoolean("autosave", v).apply()

    companion object {
        val RESOLUTIONS = listOf(480, 720, 1080, 0)
        val RESOLUTION_LABELS = listOf("480p", "720p", "1080p", "Asli")
        val FPS_OPTIONS = listOf(24, 30, 60)
        val BITRATE_OPTIONS = listOf(2, 4, 6, 8, 12)
        val ASPECT_LABELS = listOf("Penuh", "16:9", "4:3", "Custom")

        const val ASPECT_FULL = 0

        /** Rasio lebar/tinggi untuk mode aspek, null bila bebas (custom). */
        fun ratioFor(aspect: Int): Float? = when (aspect) {
            1 -> 16f / 9f
            2 -> 4f / 3f
            else -> null
        }
    }
}
