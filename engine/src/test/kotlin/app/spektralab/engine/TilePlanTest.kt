package app.spektralab.engine

import app.spektralab.gl.TilePlan
import app.spektralab.raw.CfaPattern
import app.spektralab.raw.RawDeveloper
import app.spektralab.raw.RawImage
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Structure of [TilePlan] (the pixel equivalence with the untiled render is checked in
 * WebGL by tools/glcheck/tiles.js): coverage, window bounds, and the GPU memory a full-size
 * print needs, which must not grow with the tile count.
 */
class TilePlanTest {
    private val data = EngineData { File(System.getProperty("spektra.assets"), it).inputStream() }
    private val look = Look(film = "kodak_portra_400", halation = 2.0, diffusion = "cinebloom", diffusionAmount = 1.0)

    private fun plan(
        w: Int, h: Int, pad: Int, tile: Int, flipY: Boolean = true,
        customLook: Look = look, sw: Int = w, sh: Int = h,
        uv: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f, 1f),
    ): TilePlan {
        val s = LookSetup.create(customLook, data, 24)
        val px = s.pixelSizeUm(max(w, h))
        val ow = w + 2 * pad; val oh = h + 2 * pad
        val rect = floatArrayOf(pad.toFloat() / ow, pad.toFloat() / oh, (pad + w).toFloat() / ow, (pad + h).toFloat() / oh)
        return TilePlan.build(w, h, ow, oh, rect, flipY, sw, sh, uv, s.exposureBlur(px, max(w, h) / 3.0), s.couplerBlur(px, max(w, h) / 3.0),
            s.couplersActive, tile)
    }

    @Test
    fun everyOutputPixelAndGlobalTexelIsWrittenOnce() {
        val noHalationNoCouplers = Look(film = "fujifilm_velvia_100", halation = 0.0, diffusion = "off")
        for (l in listOf(look, noHalationNoCouplers)) {
            for (flip in listOf(true, false)) {
                val p = plan(1237, 803, 37, 200, flip, customLook = l)
                val out = IntArray(p.outWidth * p.outHeight)
                val global = HashMap<String, IntArray>()
                for (op in p.ops) when (op) {
                    is TilePlan.Op.Final -> for (y in op.y0 until op.y0 + op.h) for (x in op.x0 until op.x0 + op.w) out[y * p.outWidth + x]++
                    is TilePlan.Op.Down -> if (op.viewport !== op.dst) {
                        val a = global.getOrPut(op.dst.name) { IntArray(op.dst.w * op.dst.h) }
                        for (y in op.viewport.y0 until op.viewport.y0 + op.viewport.h) for (x in op.viewport.x0 until op.viewport.x0 + op.viewport.w) a[y * op.dst.w + x]++
                    }
                    else -> {}
                }
                assertTrue(out.all { it == 1 }, "output tiles overlap or leave gaps")
                if (l === look) assertTrue(global.isNotEmpty())
                for ((n, a) in global) assertTrue(a.all { it == 1 }, "$n: tile cores overlap or leave gaps")
            }
        }
    }

    @Test
    fun windowsAndSourceUploadsStayInsideBounds() {
        val sw = 1600; val sh = 1200
        // center crop to 1237x803 aspect
        val ch = ((sw.toDouble() / sh) / (1237.0 / 803.0)).toFloat()
        val y0 = (1f - ch) / 2f; val y1 = (1f + ch) / 2f
        val uv = floatArrayOf(1f, 0f, 0f, 0f, -(y1 - y0), 1f - y0, 0f, 0f, 1f)
        val p = plan(1237, 803, 0, 160, sw = sw, sh = sh, uv = uv)
        var w = 1237; var h = 803
        val sizes = ArrayList<Pair<Int, Int>>()
        repeat(14) { sizes += w to h; w = (w + 1) / 2; h = (h + 1) / 2 }
        fun check(b: TilePlan.Buf?) {
            if (b == null) return
            val (lw, lh) = sizes[b.level]
            assertTrue(b.x0 >= 0 && b.y0 >= 0 && b.w > 0 && b.h > 0 && b.x0 + b.w <= lw && b.y0 + b.h <= lh, "${b.name} outside level ${b.level}")
        }
        for (op in p.ops) when (op) {
            is TilePlan.Op.Source -> assertTrue(op.x0 >= 0 && op.y0 >= 0 && op.w > 0 && op.h > 0 && op.x0 + op.w <= sw && op.y0 + op.h <= sh,
                "source window ${op.x0},${op.y0} ${op.w}x${op.h} outside ${sw}x$sh")
            is TilePlan.Op.Expose -> check(op.dst)
            is TilePlan.Op.Down -> { check(op.dst); check(op.viewport); check(op.src) }
            is TilePlan.Op.Blur -> { check(op.dst); check(op.src) }
            is TilePlan.Op.Combine -> { check(op.dst); check(op.base); op.terms.forEach { check(it.src) } }
            is TilePlan.Op.Corr -> { check(op.dst); check(op.l1); check(op.s) }
            is TilePlan.Op.Develop -> { check(op.dst); check(op.raw); check(op.s); check(op.cb) }
            is TilePlan.Op.Final -> check(op.density)
            else -> {}
        }
    }

    @Test
    fun tooSmallToTile() {
        assertFailsWith<IllegalArgumentException> { plan(5000, 20, 0, 1024) }
        assertFailsWith<IllegalArgumentException> { plan(0, 1000, 0, 1024) }
    }

    /** Peak GPU memory of a plan: RGBA16F targets by name (reallocated on a size change), freed by Free ops. */
    private fun peakBytes(p: TilePlan): Long {
        val live = HashMap<String, Long>()
        var peak = 0L
        fun use(b: TilePlan.Buf?) { if (b != null) live[b.name] = b.w.toLong() * b.h * 8; peak = max(peak, live.values.sum()) }
        var source = 0L
        for (op in p.ops) {
            when (op) {
                // the largest source upload: half-float (raw) or sRGB8 data
                is TilePlan.Op.Source -> source = max(source, op.w.toLong() * op.h * 8)
                is TilePlan.Op.Expose -> use(op.dst)
                is TilePlan.Op.Down -> use(op.dst)
                is TilePlan.Op.Blur -> { use(op.tmp); use(op.dst) }
                is TilePlan.Op.Combine -> use(op.dst)
                is TilePlan.Op.Corr -> use(op.dst)
                is TilePlan.Op.Develop -> use(op.dst)
                is TilePlan.Op.Final -> live["out"] = op.w.toLong() * op.h * 4
                is TilePlan.Op.Free -> op.names.forEach { live.remove(it) }
            }
        }
        return peak + source
    }

    @Test
    fun fullSizeMemoryIsBounded() {
        for ((w, h) in listOf(8160 to 6120, 12000 to 9000)) {
            val p = plan(w, h, (max(w, h) * 0.03).toInt(), 1024)
            val mb = peakBytes(p) / 1e6
            val globalMb = p.ops.filterIsInstance<TilePlan.Op.Blur>().filter { it.dst.name.startsWith("g") }
                .sumOf { it.dst.w.toLong() * it.dst.h * 8 } / 1e6
            println("tiled ${w}x$h: margin ${p.margin} px, ${p.ops.size} ops, peak GPU %.0f MB (kept global octaves %.0f MB)".format(mb, globalMb))
            // whole-image half-float buffers would be 8 bytes/px each (400 MB at 50 MP)
            assertTrue(mb < 200, "peak $mb MB")
        }
    }

    @Test
    fun rawDevelopmentFitsTheHeapBudget() {
        val budget = 200L shl 20
        val dev = RawDeveloper
        // 12 MP: full resolution, unchanged
        assertEquals(12000, dev.longEdgeWithin(4000, 3000, false, 12000, false, budget, outputOnHeap = false))
        // 24 MP fits once the result is off the heap (144 MB), but not on it (336 MB)
        assertEquals(12000, dev.longEdgeWithin(6000, 4000, false, 12000, false, budget, outputOnHeap = false))
        assertEquals(3000, dev.longEdgeWithin(6000, 4000, false, 12000, false, budget, outputOnHeap = true))
        // 50 MP: the denoise planes alone would be 300 MB: binned 2x2 (75 MB)
        val e50 = dev.longEdgeWithin(8160, 6120, false, 12000, false, budget, outputOnHeap = false)
        assertEquals(4080, e50)
        assertTrue(dev.workingBytes(8160, 6120, false, e50, false, false) <= budget)
        // odd 50 MP dimensions also fit within budget
        val eOdd = dev.longEdgeWithin(8161, 6121, false, 12000, false, budget, outputOnHeap = false)
        assertTrue(dev.workingBytes(8161, 6121, false, eOdd, false, false) <= budget)
        // a merged (demosaiced) 50 MP frame: binned 2x2 as well
        assertEquals(4080, dev.longEdgeWithin(8160, 6120, true, 12000, false, budget, outputOnHeap = false))
        assertTrue(dev.workingBytes(8160, 6120, true, 4080, false, false) <= budget)

        // verify custom allocator & orientation swap on a real RawImage
        val w = 64; val h = 48
        val data = ShortArray(w * h) { 512 }
        val raw = RawImage(w, h, data, CfaPattern.RGGB, FloatArray(4) { 64f }, 1023f, 0, 0, w, h,
            floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), orientation = 6)
        var allocated = 0
        val out = dev.develop(raw, dev.longEdgeWithin(raw, 12000, false, budget, outputOnHeap = false), false,
            allocate = { allocated = it; ByteBuffer.allocateDirect(it) })
        assertEquals(w * h * 8, allocated)
        assertEquals(h, out.width)
        assertEquals(w, out.height)
    }
}
