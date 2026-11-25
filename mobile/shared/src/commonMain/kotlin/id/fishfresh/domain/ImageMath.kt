package id.fishfresh.domain

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/** Citra RGB, piksel dikemas 0xRRGGBB (alpha diabaikan). */
class Rgb(val width: Int, val height: Int, val px: IntArray) {
    init { require(px.size == width * height) }
    fun r(i: Int) = (px[i] shr 16) and 0xFF
    fun g(i: Int) = (px[i] shr 8) and 0xFF
    fun b(i: Int) = px[i] and 0xFF
}

/**
 * Praproses identik dengan pipeline pelatihan (Python):
 * - crop mata  = src/fishfresh/eye_roi.py::crop_eye + circle_mask  (Pillow LANCZOS, 8-bit)
 * - citra utuh = tf.image.resize(bilinear, antialias=True)          (float, tanpa pembulatan)
 * Resampler meniru Pillow Resample.c (koefisien fixed-point 22 bit, pass horizontal lalu vertikal).
 */
object ImageMath {
    private const val PRECISION_BITS = 32 - 8 - 2

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else (x * PI).let { sin(it) / it }
    private fun lanczos(x: Double): Double = if (x >= -3.0 && x < 3.0) sinc(x) * sinc(x / 3.0) else 0.0
    private fun triangle(x: Double): Double = (if (x < 0) -x else x).let { if (it < 1.0) 1.0 - it else 0.0 }

    private class Coeffs(val bounds: IntArray, val weights: DoubleArray, val ksize: Int)

    private fun coeffs(inSize: Int, outSize: Int, support0: Double, filter: (Double) -> Double): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = support0 * filterScale
        val ksize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val k = DoubleArray(outSize * ksize)
        val ss = 1.0 / filterScale
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt().coerceAtLeast(0)
            var xmax = (center + support + 0.5).toInt().coerceAtMost(inSize) - xmin
            if (xmin < 0) xmin = 0
            var ww = 0.0
            for (x in 0 until xmax) {
                val w = filter((x + xmin - center + 0.5) * ss)
                k[xx * ksize + x] = w
                ww += w
            }
            if (ww != 0.0) for (x in 0 until xmax) k[xx * ksize + x] /= ww
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(bounds, k, ksize)
    }

    private fun fixed(c: Coeffs): IntArray = IntArray(c.weights.size) { i ->
        val w = c.weights[i] * (1 shl PRECISION_BITS)
        (if (w < 0) -0.5 + w else 0.5 + w).toInt()
    }

    private fun clip8(v: Int): Int = when {
        v >= (1 shl PRECISION_BITS shl 8) -> 255
        v <= 0 -> 0
        else -> v shr PRECISION_BITS
    }

    /** Pillow Image.resize(size, LANCZOS) untuk RGB 8-bit. */
    fun resizeLanczos8(src: Rgb, outW: Int, outH: Int): Rgb {
        var img = src
        if (outW != img.width) {
            val c = coeffs(img.width, outW, 3.0, ::lanczos)
            val kk = fixed(c)
            val out = IntArray(outW * img.height)
            for (y in 0 until img.height) for (xx in 0 until outW) {
                val xmin = c.bounds[xx * 2]; val xmax = c.bounds[xx * 2 + 1]
                var sr = 1 shl (PRECISION_BITS - 1); var sg = sr; var sb = sr
                for (x in 0 until xmax) {
                    val p = img.px[y * img.width + xmin + x]; val w = kk[xx * c.ksize + x]
                    sr += ((p shr 16) and 0xFF) * w; sg += ((p shr 8) and 0xFF) * w; sb += (p and 0xFF) * w
                }
                out[y * outW + xx] = (clip8(sr) shl 16) or (clip8(sg) shl 8) or clip8(sb)
            }
            img = Rgb(outW, img.height, out)
        }
        if (outH != img.height) {
            val c = coeffs(img.height, outH, 3.0, ::lanczos)
            val kk = fixed(c)
            val out = IntArray(img.width * outH)
            for (yy in 0 until outH) {
                val ymin = c.bounds[yy * 2]; val ymax = c.bounds[yy * 2 + 1]
                for (x in 0 until img.width) {
                    var sr = 1 shl (PRECISION_BITS - 1); var sg = sr; var sb = sr
                    for (y in 0 until ymax) {
                        val p = img.px[(ymin + y) * img.width + x]; val w = kk[yy * c.ksize + y]
                        sr += ((p shr 16) and 0xFF) * w; sg += ((p shr 8) and 0xFF) * w; sb += (p and 0xFF) * w
                    }
                    out[yy * img.width + x] = (clip8(sr) shl 16) or (clip8(sg) shl 8) or clip8(sb)
                }
            }
            img = Rgb(img.width, outH, out)
        }
        return img
    }

    /** Persis eye_roi.crop_eye(im, box, margin, out_size) dengan r = sisi/2 (margin sudah di r). */
    fun cropEye(src: Rgb, circle: EyeCircle, outSize: Int = 224): Rgb {
        val side = 2.0 * circle.r
        val l = round(circle.cx - side / 2).toInt()   // round() Kotlin = half-even, sama dengan Python
        val t = round(circle.cy - side / 2).toInt()
        val s = max(1, round(side).toInt())
        val canvas = IntArray(s * s)                   // hitam
        val x0 = max(l, 0); val y0 = max(t, 0)
        val x1 = min(l + s, src.width); val y1 = min(t + s, src.height)
        val dx = max(-l, 0); val dy = max(-t, 0)
        for (y in y0 until y1) for (x in x0 until x1) {
            canvas[(y - y0 + dy) * s + (x - x0 + dx)] = src.px[y * src.width + x] and 0xFFFFFF
        }
        val small = resizeLanczos8(Rgb(s, s, canvas), outSize, outSize)
        applyCircleMask(small)
        return small
    }

    /** eye_roi.circle_mask: piksel dengan (x-c)^2 + (y-c)^2 > (n/2)^2 diisi 0, c = (n-1)/2. */
    fun applyCircleMask(img: Rgb) {
        val n = img.width
        val c = (n - 1) / 2.0
        val r2 = (n / 2.0) * (n / 2.0)
        for (y in 0 until n) for (x in 0 until n) {
            val d = (x - c) * (x - c) + (y - c) * (y - c)
            if (d > r2) img.px[y * n + x] = 0
        }
    }

    /** tf.image.resize(method="bilinear", antialias=True) -> float RGB 0..255 (HWC). */
    fun resizeBilinearAntialiasFloat(src: Rgb, outW: Int, outH: Int): FloatArray {
        val cx = coeffs(src.width, outW, 1.0, ::triangle)
        val cy = coeffs(src.height, outH, 1.0, ::triangle)
        val tmp = FloatArray(outW * src.height * 3)
        for (y in 0 until src.height) for (xx in 0 until outW) {
            val xmin = cx.bounds[xx * 2]; val xmax = cx.bounds[xx * 2 + 1]
            var r = 0.0; var g = 0.0; var b = 0.0
            for (x in 0 until xmax) {
                val p = src.px[y * src.width + xmin + x]; val w = cx.weights[xx * cx.ksize + x]
                r += ((p shr 16) and 0xFF) * w; g += ((p shr 8) and 0xFF) * w; b += (p and 0xFF) * w
            }
            val o = (y * outW + xx) * 3
            tmp[o] = r.toFloat(); tmp[o + 1] = g.toFloat(); tmp[o + 2] = b.toFloat()
        }
        val out = FloatArray(outW * outH * 3)
        for (yy in 0 until outH) {
            val ymin = cy.bounds[yy * 2]; val ymax = cy.bounds[yy * 2 + 1]
            for (x in 0 until outW) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (y in 0 until ymax) {
                    val o = ((ymin + y) * outW + x) * 3; val w = cy.weights[yy * cy.ksize + y]
                    r += tmp[o] * w; g += tmp[o + 1] * w; b += tmp[o + 2] * w
                }
                val o = (yy * outW + x) * 3
                out[o] = r.toFloat().coerceIn(0f, 255f); out[o + 1] = g.toFloat().coerceIn(0f, 255f)
                out[o + 2] = b.toFloat().coerceIn(0f, 255f)
            }
        }
        return out
    }

    fun toFloatHwc(img: Rgb): FloatArray {
        val out = FloatArray(img.width * img.height * 3)
        for (i in img.px.indices) {
            out[i * 3] = img.r(i).toFloat(); out[i * 3 + 1] = img.g(i).toFloat(); out[i * 3 + 2] = img.b(i).toFloat()
        }
        return out
    }

    /**
     * Metrik kualitas di area mata (setara eye_roi.quality_metrics, cv2.Laplacian ksize=3):
     * ketajaman = variansi Laplacian di lingkaran yang dikikis 6 px; kecerahan = rerata abu-abu.
     */
    fun quality(masked: Rgb): Pair<Double, Double> {
        val n = masked.width
        val gray = DoubleArray(n * n) { i -> 0.299 * masked.r(i) + 0.587 * masked.g(i) + 0.114 * masked.b(i) }
        val c = (n - 1) / 2.0
        val rOuter = (n / 2.0) * (n / 2.0)
        val rInner = (n / 2.0 - 6) * (n / 2.0 - 6)
        fun at(x: Int, y: Int): Double {                 // BORDER_REFLECT_101
            val xx = if (x < 0) -x else if (x >= n) 2 * n - 2 - x else x
            val yy = if (y < 0) -y else if (y >= n) 2 * n - 2 - y else y
            return gray[yy * n + xx]
        }
        var sum = 0.0; var sum2 = 0.0; var cnt = 0
        var bSum = 0.0; var bCnt = 0
        for (y in 0 until n) for (x in 0 until n) {
            val d = (x - c) * (x - c) + (y - c) * (y - c)
            if (d <= rOuter) { bSum += gray[y * n + x]; bCnt++ }
            if (d <= rInner) {
                val lap = 2 * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) -
                    8 * at(x, y)
                sum += lap; sum2 += lap * lap; cnt++
            }
        }
        val mean = if (cnt > 0) sum / cnt else 0.0
        val variance = if (cnt > 0) sum2 / cnt - mean * mean else 0.0
        return variance to (if (bCnt > 0) bSum / bCnt else 0.0)
    }
}
