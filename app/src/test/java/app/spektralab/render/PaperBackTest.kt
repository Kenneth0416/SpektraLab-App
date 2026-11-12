package app.spektralab.render

import androidx.test.core.app.ApplicationProvider
import app.spektralab.R
import app.spektralab.SpektraApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaperBackTest {
    @Test fun rendersNeutralPaperBackWithoutUpstreamLogo() {
        val app = ApplicationProvider.getApplicationContext<SpektraApp>()
        assertEquals("Silvercam", app.getString(R.string.app_name))
        val bmp = PaperBack.render(app, 240, 160, seed = 7)
        assertEquals(240, bmp.width)
        assertEquals(160, bmp.height)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val distinct = pixels.asSequence().map { it and 0xFF }.toSet()
        assertTrue("paper back contains subtle geometric/glare tonal range", distinct.size > 5)
    }
}
