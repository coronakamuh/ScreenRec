package com.screenrec.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lapisan layar penuh untuk memilih area rekam.
 * ratio = lebar/tinggi untuk kotak berukuran terkunci (16:9, 4:3), null = bebas.
 * Koordinat hasil = piksel layar asli.
 */
class RegionSelectorView(
    context: Context,
    private val ratio: Float?,
    private val onStart: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private val d = resources.displayMetrics.density
    private var ratioCur: Float? = ratio
    private val rect = RectF()
    private var inited = false

    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFFFFFFFF.toInt()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x55FFFFFF
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Ui.ACCENT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 13f * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 15f * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E8F0.toInt(); textSize = 14f * d; textAlign = Paint.Align.CENTER
    }

    private val startBtn = RectF()
    private val cancelBtn = RectF()
    private val rotateBtn = RectF()

    private val minSize = 120f * d
    private val hitR = 40f * d

    private enum class Mode { NONE, MOVE, RESIZE, BUTTON }

    private var mode = Mode.NONE
    private var corner = 0           // 0=kiri-atas 1=kanan-atas 2=kiri-bawah 3=kanan-bawah
    private var pressedBtn = 0       // 1 mulai, 2 batal, 3 putar
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!inited) {
            fitInitial()
            inited = true
        }
        layoutButtons()
    }

    private fun fitInitial() {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = ratioCur
        if (r == null) {
            rect.set(w * 0.1f, h * 0.25f, w * 0.9f, h * 0.65f)
        } else {
            fitRatio(w / 2f, h / 2f)
        }
    }

    private fun fitRatio(cx: Float, cy: Float) {
        val r = ratioCur ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        var bw = w * 0.8f
        var bh = bw / r
        if (bh > h * 0.7f) {
            bh = h * 0.7f
            bw = bh * r
        }
        val l = (cx - bw / 2f).coerceIn(0f, w - bw)
        val t = (cy - bh / 2f).coerceIn(0f, h - bh)
        rect.set(l, t, l + bw, t + bh)
    }

    private fun layoutButtons() {
        val bh = 48f * d
        val gap = 10f * d
        val startW = 130f * d
        val cancelW = 100f * d
        val rotW = 100f * d
        val showRot = ratioCur != null
        val total = startW + gap + cancelW + if (showRot) gap + rotW else 0f
        var x = (width - total) / 2f
        val y = height - bh - 72f * d
        cancelBtn.set(x, y, x + cancelW, y + bh)
        x += cancelW + gap
        if (showRot) {
            rotateBtn.set(x, y, x + rotW, y + bh)
            x += rotW + gap
        } else {
            rotateBtn.setEmpty()
        }
        startBtn.set(x, y, x + startW, y + bh)
    }

    // ------------------------------------------------------------ gambar

    override fun onDraw(c: Canvas) {
        // Gelapkan seluruh layar kecuali area terpilih
        c.save()
        c.clipOutRect(rect)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        c.restore()

        // Garis bantu 3x3
        val w3 = rect.width() / 3f
        val h3 = rect.height() / 3f
        for (i in 1..2) {
            c.drawLine(rect.left + w3 * i, rect.top, rect.left + w3 * i, rect.bottom, gridPaint)
            c.drawLine(rect.left, rect.top + h3 * i, rect.right, rect.top + h3 * i, gridPaint)
        }
        c.drawRect(rect, borderPaint)

        // Pegangan sudut
        for (i in 0..3) {
            val (cx, cy) = cornerPoint(i)
            c.drawCircle(cx, cy, 11f * d, handleFill)
            c.drawCircle(cx, cy, 11f * d, handleStroke)
        }

        // Label ukuran
        val label = "${rect.width().toInt()} × ${rect.height().toInt()}"
        val ly = if (rect.top > 40f * d) rect.top - 14f * d else rect.bottom + 28f * d
        c.drawText(label, rect.centerX(), ly, labelPaint)

        c.drawText(
            if (ratioCur == null) "Tarik sudut untuk mengubah ukuran, geser untuk memindah"
            else "Geser kotak, tarik sudut untuk mengubah ukuran",
            width / 2f, 64f * d, hintPaint
        )

        // Tombol
        drawBtn(c, cancelBtn, "Batal", 0xFF334155.toInt(), 0xFF334155.toInt(), pressedBtn == 2)
        if (!rotateBtn.isEmpty) drawBtn(c, rotateBtn, "Putar", 0xFF334155.toInt(), 0xFF334155.toInt(), pressedBtn == 3)
        drawBtn(c, startBtn, "Mulai", Ui.ACCENT, Ui.ACCENT2, pressedBtn == 1)
    }

    private fun drawBtn(c: Canvas, r: RectF, label: String, c1: Int, c2: Int, pressed: Boolean) {
        btnPaint.shader = android.graphics.LinearGradient(r.left, r.top, r.right, r.top, c1, c2, android.graphics.Shader.TileMode.CLAMP)
        btnPaint.alpha = if (pressed) 170 else 255
        c.drawRoundRect(r, 16f * d, 16f * d, btnPaint)
        c.drawText(label, r.centerX(), r.centerY() + btnText.textSize / 3f, btnText)
    }

    private fun cornerPoint(i: Int): Pair<Float, Float> = when (i) {
        0 -> rect.left to rect.top
        1 -> rect.right to rect.top
        2 -> rect.left to rect.bottom
        else -> rect.right to rect.bottom
    }

    // ----------------------------------------------------------- sentuhan

    private fun hitButton(x: Float, y: Float): Int = when {
        startBtn.contains(x, y) -> 1
        cancelBtn.contains(x, y) -> 2
        !rotateBtn.isEmpty && rotateBtn.contains(x, y) -> 3
        else -> 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                val b = hitButton(e.x, e.y)
                if (b != 0) {
                    mode = Mode.BUTTON
                    pressedBtn = b
                    invalidate()
                    return true
                }
                var best = -1
                var bestDist = hitR
                for (i in 0..3) {
                    val (cx, cy) = cornerPoint(i)
                    val dist = hypot(e.x - cx, e.y - cy)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = i
                    }
                }
                mode = when {
                    best >= 0 -> {
                        corner = best
                        Mode.RESIZE
                    }
                    rect.contains(e.x, e.y) -> Mode.MOVE
                    else -> Mode.NONE
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    val nl = (rect.left + dx).coerceIn(0f, width - rect.width())
                    val nt = (rect.top + dy).coerceIn(0f, height - rect.height())
                    rect.offsetTo(nl, nt)
                    lastX = e.x
                    lastY = e.y
                    invalidate()
                }

                Mode.RESIZE -> {
                    resize(e.x, e.y)
                    invalidate()
                }

                Mode.BUTTON -> {
                    if (hitButton(e.x, e.y) != pressedBtn) {
                        pressedBtn = 0
                        invalidate()
                    }
                }

                Mode.NONE -> {}
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.BUTTON) {
                    val b = pressedBtn
                    pressedBtn = 0
                    invalidate()
                    if (b != 0 && hitButton(e.x, e.y) == b) perform(b)
                }
                mode = Mode.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedBtn = 0
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun perform(b: Int) {
        when (b) {
            1 -> {
                // genapkan koordinat agar encoder tidak bermasalah
                val l = rect.left.toInt() and 1.inv()
                val t = rect.top.toInt() and 1.inv()
                val r = (rect.right.toInt() and 1.inv()).coerceAtLeast(l + 16)
                val bt = (rect.bottom.toInt() and 1.inv()).coerceAtLeast(t + 16)
                onStart(Rect(l, t, r, bt))
            }

            2 -> onCancel()
            3 -> {
                val r = ratioCur ?: return
                ratioCur = 1f / r
                fitRatio(rect.centerX(), rect.centerY())
                invalidate()
            }
        }
    }

    private fun resize(x: Float, y: Float) {
        val sx = if (corner == 1 || corner == 3) 1f else -1f
        val sy = if (corner == 2 || corner == 3) 1f else -1f
        // sudut lawan tetap diam
        val ax = if (sx > 0) rect.left else rect.right
        val ay = if (sy > 0) rect.top else rect.bottom
        val W = width.toFloat()
        val H = height.toFloat()
        val r = ratioCur

        if (r == null) {
            var nx = x.coerceIn(0f, W)
            var ny = y.coerceIn(0f, H)
            nx = if (sx > 0) max(nx, ax + minSize) else min(nx, ax - minSize)
            ny = if (sy > 0) max(ny, ay + minSize) else min(ny, ay - minSize)
            nx = nx.coerceIn(0f, W)
            ny = ny.coerceIn(0f, H)
            rect.set(min(nx, ax), min(ny, ay), max(nx, ax), max(ny, ay))
        } else {
            val maxW = min(if (sx > 0) W - ax else ax, (if (sy > 0) H - ay else ay) * r)
            val wx = (x - ax) * sx
            val wy = (y - ay) * sy * r
            val w = max(wx, wy).coerceAtLeast(minSize).coerceAtMost(maxW)
            val h = w / r
            val l = if (sx > 0) ax else ax - w
            val t = if (sy > 0) ay else ay - h
            rect.set(l, t, l + w, t + h)
        }
    }
}
