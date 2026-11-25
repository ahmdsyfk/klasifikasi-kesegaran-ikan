package id.fishfresh

import id.fishfresh.domain.EyeCircle
import id.fishfresh.domain.ImageMath
import id.fishfresh.domain.Rgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Praproses di HP harus identik dengan pipeline pelatihan (fixture dari Pillow & TensorFlow). */
class PreprocessParityTest {
    private val src = Rgb(PreprocessFixture.W, PreprocessFixture.H, PreprocessFixture.src)

    @Test
    fun cropMataSamaDenganEyeRoiCropEye() {
        PreprocessFixture.cases.forEachIndexed { k, c ->
            val got = ImageMath.cropEye(src, EyeCircle(c.cx, c.cy, c.r), c.n)
            var maxDiff = 0
            for (i in got.px.indices) for (s in intArrayOf(16, 8, 0)) {
                val d = abs(((got.px[i] shr s) and 0xFF) - ((c.expected[i] shr s) and 0xFF))
                if (d > maxDiff) maxDiff = d
            }
            assertEquals(0, maxDiff, "kasus $k: selisih piksel maksimum")
        }
    }

    @Test
    fun resizeCitraUtuhSamaDenganTfBilinearAntialias() {
        val got = ImageMath.resizeBilinearAntialiasFloat(src, PreprocessFixture.TF_W, PreprocessFixture.TF_H)
        val maxDiff = got.indices.maxOf { abs(got[it] - PreprocessFixture.tfBilinear[it]) }
        assertTrue(maxDiff < 1e-2f, "selisih maksimum $maxDiff")
    }
}
