package com.novacami.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/** The "Be right back" picture shown to the PC instead of the camera. Drawn once, in the shape of the stream. */
object BrbCard {
    fun create(outW: Int, outH: Int, title: String = "Be right back", sub: String = "Back in a moment"): Bitmap {
        val k = 960f / maxOf(outW, outH)
        val w = (outW * k).toInt().coerceAtLeast(64)
        val h = (outH * k).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val m = minOf(w, h).toFloat()
        val cx = w / 2f

        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1A1F2C.toInt(), 0xFF11141E.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(cx, h * 0.40f, m * 0.6f, 0x33009DFF, 0x00009DFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // the Novacami camera mark
        val s = m * 0.15f
        val cy = h * 0.38f
        p.color = 0xFF2F8BFF.toInt()
        c.drawRoundRect(RectF(cx - s, cy - s, cx + s, cy + s), s * 0.55f, s * 0.55f, p)
        p.color = 0xFF141722.toInt()
        val t = s * 0.52f
        c.drawRoundRect(RectF(cx - t, cy - t, cx + t, cy + t), t * 0.5f, t * 0.5f, p)
        p.color = 0xFF0F121C.toInt(); c.drawCircle(cx, cy, s * 0.36f, p)
        p.color = 0xFF009DFF.toInt(); p.alpha = 150; c.drawCircle(cx, cy, s * 0.17f, p); p.alpha = 255

        p.color = 0xFFFFFFFF.toInt()
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = m * 0.10f
        c.drawText(title, cx, h * 0.64f, p)
        p.color = 0xFFB9BECB.toInt()
        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        p.textSize = m * 0.052f
        c.drawText(sub, cx, h * 0.64f + m * 0.10f, p)
        return bmp
    }
}
