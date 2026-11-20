package com.screenrec.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Merekam layar (MediaProjection -> H.264) dan suara internal
 * (AudioPlaybackCapture -> AAC), lalu menggabungkannya ke file MP4.
 */
class ScreenRecorder(
    private val context: Context,
    private val projection: MediaProjection,
    private val prefs: Prefs,
    /** Area layar yang direkam (piksel layar asli). null = layar penuh. */
    private val region: Rect? = null,
) {
    private var renderer: RegionRenderer? = null
    private var srcW = 0
    private var srcH = 0
    private var cropRect = Rect()
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var tmpFile: File? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile
    private var running = false

    private val muxLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    @Volatile
    private var hasAudio = false
    private var startUs = 0L

    private var outW = 0
    private var outH = 0

    val isRunning: Boolean get() = running

    // ---------------------------------------------------------------- start

    fun start(): Boolean {
        return try {
            setupVideo()
            setupAudio()

            MediaSaver.cleanOld(context)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(context.cacheDir, "rec_$stamp.mp4")
            tmpFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            videoTrack = -1
            audioTrack = -1
            muxerStarted = false
            startUs = System.nanoTime() / 1000
            running = true

            videoEncoder!!.start()
            val dm = realMetrics()
            virtualDisplay = if (region == null) {
                projection.createVirtualDisplay(
                    "ScreenRec", outW, outH, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null
                )
            } else {
                // Layar penuh digambar ke OpenGL, lalu hanya area terpilih dikirim ke encoder
                val r = RegionRenderer(inputSurface!!, srcW, srcH, cropRect, outW, outH)
                renderer = r
                projection.createVirtualDisplay(
                    "ScreenRec", srcW, srcH, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.start(), null, null
                )
            }
            videoThread = Thread({ drainVideo() }, "rec-video").also { it.start() }

            if (hasAudio) {
                audioEncoder!!.start()
                audioRecord!!.startRecording()
                audioThread = Thread({ audioLoop() }, "rec-audio").also { it.start() }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai rekaman", e)
            running = false
            releaseAll()
            false
        }
    }

    private fun realMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun setupVideo() {
        val dm = realMetrics()
        srcW = dm.widthPixels
        srcH = dm.heightPixels

        val area = Rect(0, 0, srcW, srcH)
        if (region != null && !area.intersect(region)) {
            throw IllegalArgumentException("Area rekam di luar layar")
        }
        cropRect = area

        val areaW = area.width()
        val areaH = area.height()
        val target = prefs.resolutionHeight
        val shortSide = minOf(areaW, areaH)
        val scale = if (target <= 0 || target >= shortSide) 1f else target.toFloat() / shortSide
        outW = ((areaW * scale).toInt() / 16 * 16).coerceAtLeast(16)
        outH = ((areaH * scale).toInt() / 16 * 16).coerceAtLeast(16)

        val fps = prefs.fps
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, prefs.bitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Ulangi frame terakhir bila layar diam, supaya video tidak "membeku" terhadap audio
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = enc.createInputSurface()
        videoEncoder = enc
    }

    private fun setupAudio() {
        hasAudio = false
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBuf * 2, 16384))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }

            val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            audioRecord = rec
            audioEncoder = enc
            hasAudio = true
        } catch (e: Exception) {
            Log.w(TAG, "Suara internal tidak tersedia, merekam video saja", e)
            audioRecord?.release()
            audioRecord = null
            audioEncoder?.release()
            audioEncoder = null
            hasAudio = false
        }
    }

    // ------------------------------------------------------------ muxer util

    /** Harus dipanggil di dalam synchronized(muxLock). */
    private fun tryStartMuxer() {
        if (muxerStarted) return
        if (videoTrack < 0) return
        if (hasAudio && audioTrack < 0) return
        muxer!!.start()
        muxerStarted = true
        muxLock.notifyAll()
    }

    private fun writeSample(track: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            while (!muxerStarted && running) {
                try {
                    muxLock.wait(20)
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (muxerStarted) {
                try {
                    muxer!!.writeSampleData(track, buf, info)
                } catch (e: Exception) {
                    Log.w(TAG, "writeSampleData gagal", e)
                }
            }
        }
    }

    // ----------------------------------------------------------------- video

    private fun drainVideo() {
        val enc = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var lastPts = -1L
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                        videoTrack = muxer!!.addTrack(enc.outputFormat)
                        tryStartMuxer()
                    }

                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null && videoTrack >= 0) {
                            var pts = info.presentationTimeUs - startUs
                            if (pts < 0) pts = 0
                            if (pts <= lastPts) pts = lastPts + 1
                            lastPts = pts
                            info.presentationTimeUs = pts
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            writeSample(videoTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder video berhenti", e)
        }
    }

    // ----------------------------------------------------------------- audio

    private fun audioFailed() {
        synchronized(muxLock) {
            if (audioTrack < 0) {
                hasAudio = false
                tryStartMuxer()
            }
        }
    }

    private fun audioLoop() {
        val rec = audioRecord ?: return
        val enc = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var totalFrames = 0L
        var firstPts = -1L
        var eosQueued = false
        var outputDone = false

        try {
            while (!outputDone) {
                // 1) isi encoder dengan PCM dari AudioRecord
                if (!eosQueued) {
                    val inIdx = enc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        if (!running) {
                            val pts = if (firstPts < 0) 0 else firstPts + totalFrames * 1_000_000L / SAMPLE_RATE
                            enc.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val ib = enc.getInputBuffer(inIdx)!!
                            ib.clear()
                            val n = rec.read(ib, minOf(ib.remaining(), CHUNK_BYTES))
                            if (n < 0) {
                                Log.e(TAG, "AudioRecord.read error $n")
                                enc.queueInputBuffer(inIdx, 0, 0, 0, 0)
                                break
                            }
                            val frames = n / 4
                            if (firstPts < 0) {
                                val nowUs = System.nanoTime() / 1000 - startUs
                                firstPts = (nowUs - frames * 1_000_000L / SAMPLE_RATE).coerceAtLeast(0)
                            }
                            val pts = firstPts + totalFrames * 1_000_000L / SAMPLE_RATE
                            totalFrames += frames
                            enc.queueInputBuffer(inIdx, 0, n, pts, 0)
                        }
                    }
                }

                // 2) ambil hasil AAC dari encoder
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(info, 0)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(muxLock) {
                            audioTrack = muxer!!.addTrack(enc.outputFormat)
                            tryStartMuxer()
                        }
                    } else if (outIdx >= 0) {
                        val buf = enc.getOutputBuffer(outIdx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null && audioTrack >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            writeSample(audioTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    } else {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Loop audio berhenti", e)
        } finally {
            audioFailed() // jika track audio belum sempat dibuat, jangan blokir video
        }
    }

    // ------------------------------------------------------------------ stop

    /** Menghentikan rekaman dan menyimpan ke Movies/ScreenRec. Panggil dari thread latar. */
    fun stop(): RecordingResult? {
        if (!running) return null

        virtualDisplay?.release()
        virtualDisplay = null
        renderer?.stop()
        renderer = null
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream", e)
        }

        running = false
        synchronized(muxLock) { muxLock.notifyAll() }

        videoThread?.join(5000)
        audioThread?.join(5000)

        var ok = false
        synchronized(muxLock) {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "muxer.stop gagal", e)
                }
            }
        }
        releaseAll()

        val file = tmpFile
        if (!ok || file == null || !file.exists() || file.length() <= 0) return null
        val uri = if (prefs.autosave) MediaSaver.save(context, file) else null
        return RecordingResult(file, uri)
    }

    private fun releaseAll() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { renderer?.stop() } catch (_: Exception) {}
        renderer = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
    }


    companion object {
        private const val TAG = "ScreenRecorder"
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_BYTES = 4096
    }
}

/** Hasil rekaman: file sementara di cache, dan Uri galeri bila autosave aktif. */
data class RecordingResult(val file: File, val savedUri: Uri?)
