package com.screenrec.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Satu pilihan pada menu melingkar. */
class RadialItem(
    val label: String,
    val kind: Int,
    val enabled: Boolean,
    val onClick: () -> Unit,
) {
    companion object {
        const val PLAY = 0
        const val STOP = 1
        const val SETTINGS = 2
        const val CLOSE = 3
    }
}

/** Lingkaran berisi ikon (mulai, berhenti, pengaturan, tutup) yang digambar sendiri. */
class RadialIconView(context: Context, private val kind: Int, private val enabledItem: Boolean) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * d
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0x33FFFFFF
    }

    init {
        alpha = if (enabledItem) 1f else 0.35f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f - 2f * d

        val (c1, c2) = when (kind) {
            RadialItem.PLAY -> Ui.OK to 0xFF059669.toInt()
            RadialItem.STOP -> Ui.DANGER to Ui.DANGER2
            RadialItem.SETTINGS -> Ui.ACCENT to Ui.ACCENT2
            else -> 0xFF334155.toInt() to 0xFF1E293B.toInt()
        }
        fill.shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)

        when (kind) {
            RadialItem.PLAY -> {
                val s = r * 0.5f
                val p = Path().apply {
                    moveTo(cx - s * 0.6f, cy - s)
                    lineTo(cx + s * 1.0f, cy)
                    lineTo(cx - s * 0.6f, cy + s)
                    close()
                }
                c.drawPath(p, ink)
            }

            RadialItem.STOP -> {
                val s = r * 0.42f
                c.drawRoundRect(RectF(cx - s, cy - s, cx + s, cy + s), 3f * d, 3f * d, ink)
            }

            RadialItem.SETTINGS -> {
                // tiga garis penggeser dengan bulatan, seperti ikon "sliders"
                val half = r * 0.5f
                val ys = floatArrayOf(cy - r * 0.38f, cy, cy + r * 0.38f)
                val xs = floatArrayOf(cx - half * 0.35f, cx + half * 0.4f, cx - half * 0.1f)
                for (i in 0..2) {
                    c.drawLine(cx - half, ys[i], cx + half, ys[i], stroke)
                    c.drawCircle(xs[i], ys[i], 4.2f * d, ink)
                }
            }

            else -> {
                val s = r * 0.32f
                c.drawLine(cx - s, cy - s, cx + s, cy + s, stroke)
                c.drawLine(cx + s, cy - s, cx - s, cy + s, stroke)
            }
        }
    }
}

/**
 * Menu melingkar beranimasi: pilihan muncul mengipas dari titik (cx, cy)
 * ke arah tengah layar. Ketuk area kosong atau tombol tengah untuk menutup.
 */
class RadialMenu(
    context: Context,
    private val cx: Float,
    private val cy: Float,
    private val screenW: Int,
    private val screenH: Int,
    private val items: List<RadialItem>,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private val itemW = (88 * d).toInt()
    private val itemH = (84 * d).toInt()
    private val iconSize = (56 * d).toInt()
    private val radius = 104f * d

    private val containers = mutableListOf<View>()
    private val finalPos = mutableListOf<Pair<Float, Float>>()
    private var center: View? = null
    private var closing = false

    init {
        setBackgroundColor(0x80020617.toInt())
        alpha = 0f
        setOnClickListener { close { onDismiss() } }
        build()
        post { animateIn() }
    }

    private fun build() {
        // arah kipas = menuju tengah layar (atau ke atas bila sudah di tengah)
        var dx = screenW / 2f - cx
        var dy = screenH / 2f - cy
        if (hypot(dx, dy) < 40f * d) {
            dx = 0f
            dy = -1f
        }
        val base = atan2(dy, dx)
        val step = Math.toRadians(58.0).toFloat()
        val n = items.size
        val margin = 8f * d

        items.forEachIndexed { i, item ->
            val a = base + (i - (n - 1) / 2f) * step
            var fx = cx + radius * cos(a)
            var fy = cy + radius * sin(a)
            fx = fx.coerceIn(itemW / 2f + margin, screenW - itemW / 2f - margin)
            fy = fy.coerceIn(itemH / 2f + margin, screenH - itemH / 2f - margin)
            finalPos.add(fx to fy)

            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                alpha = 0f
                setOnClickListener {
                    if (item.enabled) close {
                        onDismiss()
                        item.onClick()
                    }
                }
            }
            box.addView(RadialIconView(context, item.kind, item.enabled), LinearLayout.LayoutParams(iconSize, iconSize))
            box.addView(TextView(context).apply {
                text = item.label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFFF1F5F9.toInt())
                setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
                setPadding(0, (4 * d).toInt(), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(box, LayoutParams(itemW, itemH).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (fx - itemW / 2f).toInt()
                topMargin = (fy - itemH / 2f).toInt()
            })
            containers.add(box)
        }

        // tombol tutup di titik asal
        val size = (48 * d).toInt()
        val closeBtn = RadialIconView(context, RadialItem.CLOSE, true).apply {
            alpha = 0f
            setOnClickListener { close { onDismiss() } }
        }
        addView(closeBtn, LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (cx - size / 2f).toInt()
            topMargin = (cy - size / 2f).toInt()
        })
        center = closeBtn
    }

    private fun animateIn() {
        animate().alpha(1f).setDuration(160).start()
        containers.forEachIndexed { i, v ->
            val (fx, fy) = finalPos[i]
            v.translationX = cx - fx
            v.translationY = cy - fy
            v.scaleX = 0.2f
            v.scaleY = 0.2f
            v.alpha = 0f
            v.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(i * 45L)
                .setDuration(340)
                .setInterpolator(OvershootInterpolator(1.7f))
                .start()
        }
        center?.apply {
            scaleX = 0.4f
            scaleY = 0.4f
            rotation = -90f
            animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(280).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    /** Animasi menutup, lalu panggil [after]. */
    fun close(after: () -> Unit) {
        if (closing) return
        closing = true
        containers.forEachIndexed { i, v ->
            val (fx, fy) = finalPos[i]
            v.animate()
                .translationX(cx - fx).translationY(cy - fy)
                .scaleX(0.2f).scaleY(0.2f).alpha(0f)
                .setStartDelay(0)
                .setDuration(180)
                .setInterpolator(AccelerateInterpolator())
                .start()
        }
        center?.animate()?.alpha(0f)?.rotation(90f)?.setDuration(160)?.start()
        animate().alpha(0f).setDuration(200).withEndAction { after() }.start()
    }
}
