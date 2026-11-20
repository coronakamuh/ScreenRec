package com.screenrec.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menyalin file rekaman dari cache ke galeri (Movies/ScreenRec). */
object MediaSaver {
    private const val TAG = "MediaSaver"

    /** Menyalin [src] ke galeri. File sumber tetap ada. Mengembalikan Uri galeri, atau null bila gagal. */
    fun save(context: Context, src: File): Uri? {
        val name = "Rekaman_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyimpan video", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Hapus file rekaman sementara yang lebih tua dari 1 hari. */
    fun cleanOld(context: Context) {
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        context.cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("rec_") && it.name.endsWith(".mp4") && it.lastModified() < limit) it.delete()
        }
    }
}
