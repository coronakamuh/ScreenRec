package com.screenrec.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.TextView

/** Palet warna ala Tailwind (slate + indigo) dan helper pembuat tampilan. */
object Ui {
    val BG = 0xFF020617.toInt()        // slate-950
    val SURFACE = 0xFF0F172A.toInt()   // slate-900
    val BORDER = 0xFF1E293B.toInt()    // slate-800
    val CHIP = 0xFF1E293B.toInt()      // slate-800
    val TEXT = 0xFFF1F5F9.toInt()      // slate-100
    val SUBTLE = 0xFFCBD5E1.toInt()    // slate-300
    val MUTED = 0xFF94A3B8.toInt()     // slate-400
    val ACCENT = 0xFF6366F1.toInt()    // indigo-500
    val ACCENT2 = 0xFF8B5CF6.toInt()   // violet-500
    val DANGER = 0xFFF43F5E.toInt()    // rose-500
    val DANGER2 = 0xFFE11D48.toInt()   // rose-600
    val OK = 0xFF10B981.toInt()        // emerald-500

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    fun gradient(ctx: Context, c1: Int, c2: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    /** Beri efek sentuhan (ripple) pada sebuah latar bulat. */
    fun ripple(ctx: Context, content: Drawable, radiusDp: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            content,
            rounded(ctx, Color.WHITE, radiusDp)
        )

    fun text(ctx: Context, s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
}
