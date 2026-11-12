package app.spektralab.engine

import app.spektralab.gl.TilePlan
import java.io.File
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

    private fun plan(w: Int, h: Int, pad: Int, tile: Int, flipY: Boolean = true): TilePlan {
        val s = LookSetup.create(look, data, 24)
        val px = s.pixelSizeUm(max(w, h))
        val ow = w + 2 * pad; val oh = h + 2 * pad
        val rect = floatArrayOf(pad.toFloat() / ow, pad.toFloat() / oh, (pad + w).toFloat() / ow, (pad + h).toFloat() / oh)
        val uv = floatArrayOf(1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f, 1f)
        return TilePlan.build(w, h, ow, oh, rect, flipY, w, h, uv, s.exposureBlur(px, max(w, h) / 3.0), s.couplerBlur(px, max(w, h) / 3.0),
            s.couplersActive, tile)
    }

    @Test
    fun everyOutputPixelAndGlobalTexelIsWrittenOnce() {
        for (flip in listOf(true, false)) {
            val p = plan(1237, 803, 37, 200, flip)
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
            assertTrue(global.isNotEmpty())
            for ((n, a) in global) assertTrue(a.all { it == 1 }, "$n: tile cores overlap or leave gaps")
        }
    }

    @Test
    fun windowsStayInsideTheirLevel() {
        val p = plan(1237, 803, 0, 160)
        var w = 1237; var h = 803
        val sizes = ArrayList<Pair<Int, Int>>()
        repeat(14) { sizes += w to h; w = (w + 1) / 2; h = (h + 1) / 2 }
        fun check(b: TilePlan.Buf?) {
            if (b == null) return
            val (lw, lh) = sizes[b.level]
            assertTrue(b.x0 >= 0 && b.y0 >= 0 && b.w > 0 && b.h > 0 && b.x0 + b.w <= lw && b.y0 + b.h <= lh, "${b.name} outside level ${b.level}")
        }
        for (op in p.ops) when (op) {
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
        fun dng(w: Int, h: Int, linear: Boolean = false) = app.spektralab.raw.DngImage(w, h, ShortArray(0), intArrayOf(0, 1, 1, 2),
            FloatArray(4), 1023f, 0, 0, w, h, floatArrayOf(1f, 1f, 1f), FloatArray(9), 1, 0f, emptyList(), linear = linear)
        val budget = 200L shl 20
        val dev = app.spektralab.raw.RawDeveloper
        // 12 MP: full resolution, unchanged
        assertEquals(12000, dev.longEdgeWithin(dng(4000, 3000), 12000, false, budget, outputOnHeap = false))
        // 24 MP fits once the result is off the heap (144 MB), but not on it (336 MB)
        assertEquals(12000, dev.longEdgeWithin(dng(6000, 4000), 12000, false, budget, outputOnHeap = false))
        assertEquals(3000, dev.longEdgeWithin(dng(6000, 4000), 12000, false, budget, outputOnHeap = true))
        // 50 MP: the denoise planes alone would be 300 MB: binned 2x2 (75 MB)
        val e50 = dev.longEdgeWithin(dng(8160, 6120), 12000, false, budget, outputOnHeap = false)
        assertEquals(4080, e50)
        assertTrue(dev.workingBytes(dng(8160, 6120), e50, false, false) <= budget)
        // a merged (demosaiced) 50 MP frame: binned 2x2 as well
        assertEquals(4080, dev.longEdgeWithin(dng(8160, 6120, linear = true), 12000, false, budget, outputOnHeap = false))
    }
}
