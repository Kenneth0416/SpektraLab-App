package app.spektralab.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundGlassTest {
    @Test
    fun groundGlassKernelIsNormalizedAndCompact() {
        val kernel = FilmRenderer.linearGaussianKernel(FilmRenderer.GROUND_GLASS_SIGMA)

        // Nine symmetric bilinear pairs plus the centre: a 37-tap Gaussian in 19 reads per axis.
        assertEquals(10, kernel.taps)
        assertEquals(1.0, kernel.energy(), 1e-6)
        // Reconstruct the actual discrete samples represented by each bilinear pair.
        // Squaring the paired offset itself misses the interpolation's added variance.
        var variance = 0.0
        for (i in 1 until kernel.taps) {
            val offset = kernel.offsets[i].toDouble()
            val left = kotlin.math.floor(offset)
            val fraction = offset - left
            variance += 2 * kernel.weights[i] *
                ((1 - fraction) * left * left + fraction * (left + 1) * (left + 1))
        }
        val measuredSigma = kotlin.math.sqrt(variance / kernel.energy())
        // A sigma-6 Gaussian truncated at 3 sigma has a measured width of ~5.936.
        assertEquals(5.936381, measuredSigma, 1e-5)
        // The browser suite also measures the fully reconstructed GPU impulse width.
    }

    @Test
    fun pairedOffsetsStayInsideTheirSourceSamples() {
        val kernel = FilmRenderer.linearGaussianKernel(FilmRenderer.GROUND_GLASS_SIGMA)
        assertEquals(0.0f, kernel.offsets[0])
        for (i in 1 until kernel.taps) {
            val first = 2 * i - 1
            assertTrue(kernel.offsets[i] >= first)
            assertTrue(kernel.offsets[i] <= first + 1)
            assertTrue(kernel.weights[i] > 0f)
            if (i > 1) assertTrue(kernel.offsets[i] > kernel.offsets[i - 1])
        }
    }

    @Test
    fun allRendererGaussianKernelsPreserveEnergy() {
        for (sigma in listOf(0.25, 0.75, 1.5, 4.0, 12.0, 28.0)) {
            val kernel = FilmRenderer.linearGaussianKernel(sigma)
            assertEquals("sigma=$sigma", 1.0, kernel.energy(), 1e-6)
            assertTrue(kernel.taps <= 16)
        }
    }

    @Test
    fun sourceSizeCapsHdAnd4kWithoutUpscaling() {
        for ((width, height) in listOf(1920 to 1080, 3840 to 2160)) {
            assertEquals(GroundGlassSizing.Size(1280, 720), GroundGlassSizing.sourceSize(width, height))
            assertEquals(GroundGlassSizing.Size(720, 1280), GroundGlassSizing.sourceSize(height, width))
        }
        for ((width, height) in listOf(1 to 1, 1 to 7, 7 to 1, 255 to 258, 1280 to 720, 720 to 1280)) {
            assertEquals(GroundGlassSizing.Size(width, height), GroundGlassSizing.sourceSize(width, height))
        }
        assertEquals(GroundGlassSizing.Size(1280, 1), GroundGlassSizing.sourceSize(4000, 1))
        assertEquals(GroundGlassSizing.Size(1, 1280), GroundGlassSizing.sourceSize(1, 4000))
        assertEquals(GroundGlassSizing.Size(1280, 1280), GroundGlassSizing.sourceSize(Int.MAX_VALUE, Int.MAX_VALUE))
        for (width in listOf(1, 257, 1279, 1280, 1281, 1920, 3840)) {
            for (height in listOf(1, 258, 720, 1280, 2160, 4000)) {
                val size = GroundGlassSizing.sourceSize(width, height)
                assertTrue(size.width in 1..width)
                assertTrue(size.height in 1..height)
                assertTrue(maxOf(size.width, size.height) <= 1280)
            }
        }
    }

    private fun LinearGaussianKernel.energy(): Double =
        weights[0].toDouble() + 2 * (1 until taps).sumOf { weights[it].toDouble() }
}
