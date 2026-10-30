package com.den.lumenvpn

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** OKLCH -> sRGB (Björn Ottosson). */
fun oklch(l: Float, c: Float, h: Float, alpha: Float = 1f): Color {
    val hr = Math.toRadians(h.toDouble())
    return oklab(l.toDouble(), c * cos(hr), c * sin(hr), alpha)
}

fun oklab(l: Double, a: Double, b: Double, alpha: Float = 1f): Color {
    val l_ = l + 0.3963377774 * a + 0.2158037573 * b
    val m_ = l - 0.1055613458 * a - 0.0638541728 * b
    val s_ = l - 0.0894841775 * a - 1.2914855480 * b
    val L = l_ * l_ * l_; val M = m_ * m_ * m_; val S = s_ * s_ * s_
    fun enc(x: Double): Float {
        val v = x.coerceIn(0.0, 1.0)
        return (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055).toFloat()
    }
    return Color(
        enc(4.0767416621 * L - 3.3077115913 * M + 0.2309699292 * S),
        enc(-1.2684380046 * L + 2.6097574011 * M - 0.3413193965 * S),
        enc(-0.0041960863 * L - 0.7034186147 * M + 1.7076147010 * S),
        alpha,
    )
}

private data class Lab(val l: Double, val a: Double, val b: Double, val alpha: Float)

private fun lab(l: Float, c: Float, h: Float, alpha: Float): Lab {
    val hr = Math.toRadians(h.toDouble())
    return Lab(l.toDouble(), c * cos(hr), c * sin(hr), alpha)
}

/** Градієнт, інтерпольований у Oklab: повертає набір стопів для Brush. */
fun oklabStops(from: Color4, to: Color4, steps: Int = 8): List<Color> {
    val x = lab(from.l, from.c, from.h, from.a)
    val y = lab(to.l, to.c, to.h, to.a)
    return (0 until steps).map { i ->
        val t = i / (steps - 1.0)
        oklab(x.l + (y.l - x.l) * t, x.a + (y.a - x.a) * t, x.b + (y.b - x.b) * t,
            (x.alpha + (y.alpha - x.alpha) * t.toFloat()))
    }
}

data class Color4(val l: Float, val c: Float, val h: Float, val a: Float = 1f)

fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
fun lerp4(a: Color4, b: Color4, t: Float) =
    Color4(lerpF(a.l, b.l, t), lerpF(a.c, b.c, t), lerpF(a.h, b.h, t), lerpF(a.a, b.a, t))

object Lumen {
    val void = oklch(0.13f, 0.012f, 255f)
    val textPrimary = oklch(0.93f, 0.010f, 250f)
    val textMuted = oklch(0.62f, 0.015f, 250f)
    val textDim = oklch(0.45f, 0.015f, 250f)
}

/** «Оптичний шовк»: Interleaved Gradient Noise (Jimenez), f = fract(52.98·fract(0.0671x + 0.00584y)). */
fun interleavedGradientNoise(size: Int = 128): ImageBitmap {
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        val inner = (0.06711056 * x + 0.00583715 * y).let { it - kotlin.math.floor(it) }
        val f = (52.9829189 * inner).let { it - kotlin.math.floor(it) }
        val v = (f * 255).toInt()
        px[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
