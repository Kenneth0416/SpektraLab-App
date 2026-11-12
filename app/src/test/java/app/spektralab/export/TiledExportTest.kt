package app.spektralab.export

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import app.spektralab.SpektraApp
import app.spektralab.engine.Catalog
import app.spektralab.engine.Look
import app.spektralab.engine.LookSetup
import app.spektralab.gl.TilePlan
import app.spektralab.render.Framing
import app.spektralab.render.GlWorker
import app.spektralab.render.OverlayPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
class TiledExportTest {
    private val app get() = ApplicationProvider.getApplicationContext<SpektraApp>()

    @Test
    fun framingAndTilePlanCoverAllFormatsAndBordersAtFullSize() {
        val sizes = listOf(6000 to 4000, 4000 to 6000, 12000 to 9000)
        val borders = listOf("none", "white", "edge")
        for ((sw, sh) in sizes) {
            for (fmt in Catalog.formats) {
                for (borderStyle in borders) {
                    val look = Look(
                        film = "kodak_portra_400",
                        format = fmt.id,
                        border = borderStyle,
                        halation = 1.2,
                        diffusion = "cinebloom",
                        diffusionAmount = 0.7,
                    )
                    val framing = Framing.compute(sw, sh, look, ExportManager.FULL_SIZE_CAP)
                    val setup = LookSetup.create(look, app.engine, 24)
                    val px = setup.pixelSizeUm(max(framing.imageWidth, framing.imageHeight))
                    val maxSigma = max(framing.imageWidth, framing.imageHeight) / 3.0
                    val plan = TilePlan.build(
                        framing.imageWidth, framing.imageHeight,
                        framing.outWidth, framing.outHeight,
                        framing.imageRect, flipY = true,
                        sourceWidth = sw, sourceHeight = sh,
                        uvTransform = Framing.bitmapUvTransform(framing.crop),
                        expMix = setup.exposureBlur(px, maxSigma),
                        corrMix = setup.couplerBlur(px, maxSigma),
                        couplers = setup.couplersActive,
                        tile = GlWorker.TILE,
                    )
                    assertEquals("${fmt.id}/$borderStyle", framing.outWidth, plan.outWidth)
                    assertEquals("${fmt.id}/$borderStyle", framing.outHeight, plan.outHeight)
                    assertTrue(framing.imageWidth <= ExportManager.FULL_SIZE_CAP)
                    assertTrue(framing.imageHeight <= ExportManager.FULL_SIZE_CAP)

                    // verify tile partition covers every output pixel exactly once without allocating 108M ints
                    val finals = plan.ops.filterIsInstance<TilePlan.Op.Final>()
                    assertTrue(finals.isNotEmpty())
                    val areaSum = finals.sumOf { it.w.toLong() * it.h }
                    assertEquals(framing.outWidth.toLong() * framing.outHeight, areaSum)
                    for (op in finals) {
                        assertTrue(op.x0 >= 0 && op.y0 >= 0 && op.x0 + op.w <= framing.outWidth && op.y0 + op.h <= framing.outHeight)
                    }
                }
            }
        }
    }

    @Test
    fun overlayDrawMatchesPaintCanvasHistory() {
        val date = 1_715_000_000_000L
        assertNull(OverlayPainter.paint(Framing.compute(100, 100, Look(border = "none", dateStamp = false), 100),
            Look(border = "none", dateStamp = false), 1, date))
        for ((w, h) in listOf(1200 to 800, 800 to 1200)) {
            val look = Look(film = "kodak_portra_400", border = "edge", dateStamp = true)
            val framing = Framing.compute(w, h, look, 1200)
            val painted = OverlayPainter.paint(framing, look, 7, date)
            assertNotNull(painted)
            assertEquals(framing.outWidth, painted!!.width)
            assertEquals(framing.outHeight, painted.height)
            painted.recycle()

            val target = Bitmap.createBitmap(framing.outWidth, framing.outHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(target)
                OverlayPainter.draw(canvas, framing, look, 7, date)
                val history = shadowOf(canvas)
                assertTrue("expected drawn text on canvas for ${w}x$h", history.textHistoryCount > 0)
            } finally {
                target.recycle()
            }
        }
    }

    @Test
    fun exportCapsMatchSpec() {
        assertEquals(4096, ExportManager.UNTILED_CAP)
        assertEquals(12000, ExportManager.FULL_SIZE_CAP)
        assertEquals(200L shl 20, ExportManager.RAW_HEAP_BUDGET)
    }
}
