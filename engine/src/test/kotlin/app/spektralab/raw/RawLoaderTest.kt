package app.spektralab.raw

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The streamed, band-parallel load path ([RawLoader], [DngReader.open], [RawDeveloper])
 * against the single-threaded whole-frame developer it replaced ([LegacyDeveloper]):
 * every developed pixel must be bit-identical.
 */
class RawLoaderTest {
    private fun bits(img: LinearImage): ShortArray {
        val b = img.halfRgba.duplicate().order(img.halfRgba.order())
        b.position(0)
        return ShortArray(img.width * img.height * 4).also { b.asShortBuffer().get(it) }
    }

    private fun assertSame(expected: LinearImage, actual: LinearImage, what: String) {
        assertEquals(expected.width, actual.width, what); assertEquals(expected.height, actual.height, what)
        assertContentEquals(bits(expected), bits(actual), what)
    }

    private val all = RawDeveloper.Options()
    private val noDenoise = RawDeveloper.Options(chromaDenoise = false)
    private val plain = RawDeveloper.Options(reconstructHighlights = false, chromaDenoise = false)

    /** Bayer, DngCreator layout; 520 x 390 gives several bands with Malvar halos, odd binning remainders. */
    private val bayer by lazy { RawFixtures.bayerDng(520, 390, seed = 7) }

    /** Merged-burst Linear DNG: 64-row deflate strips with a short last one. */
    private fun merged(w: Int = 410, h: Int = 300): ByteArray =
        ByteArrayOutputStream().also { RawFixtures.writeMerged(it, w, h, RawFixtures.mergedRgb(w, h, seed = 3)) }.toByteArray()

    @Test fun bayerStreamedAndWholeMatchTheLegacyDeveloperBitForBit() {
        val whole = assertNotNull(DngReader.read(bayer))
        val streamed = assertNotNull(DngReader.open(ByteArraySource(bayer)))
        assertNotNull(streamed.rows)
        for ((edge, fast) in listOf(4096 to false, 4096 to true, 200 to true, 120 to false, 64 to true)) for (o in listOf(all, noDenoise, plain)) {
            val ref = LegacyDeveloper.develop(whole, edge, fast, o)
            assertSame(ref, RawDeveloper.develop(whole, edge, fast, o), "whole $edge $fast")
            assertSame(ref, RawDeveloper.develop(streamed, edge, fast, o), "streamed $edge $fast")
        }
    }

    @Test fun linearStreamedAndWholeMatchTheLegacyDeveloperBitForBit() {
        val bytes = merged()
        val whole = assertNotNull(DngReader.read(bytes))
        val streamed = assertNotNull(DngReader.open(ByteArraySource(bytes)))
        assertTrue(whole.linear && streamed.linear)
        // k = 1, 2, 3 and 7 (bands that straddle strips)
        for (edge in listOf(4096, 205, 136, 58)) for (o in listOf(all, noDenoise, plain)) {
            val ref = LegacyDeveloper.develop(whole, edge, false, o)
            assertSame(ref, RawDeveloper.develop(whole, edge, false, o), "whole $edge")
            assertSame(ref, RawDeveloper.develop(streamed, edge, false, o), "streamed $edge")
        }
    }

    @Test fun fastLinearPreviewBinsLikeABayerSensor() {
        // a merged burst asked for a screen-sized preview used to be developed at full size
        val bytes = merged()
        val img = assertNotNull(DngReader.read(bytes))
        val fast = RawDeveloper.develop(img, 400, fast = true)
        assertEquals(410 / 2, maxOf(fast.width, fast.height))
        assertSame(LegacyDeveloper.develop(img, 205, false), fast, "k = 2")
        // full quality and small sizes are unchanged
        assertSame(LegacyDeveloper.develop(img, 400, false), RawDeveloper.develop(img, 400, fast = false), "full")
        assertSame(LegacyDeveloper.develop(img, 58, true), RawDeveloper.develop(img, 58, fast = true), "thumbnail")
    }

    @Test fun loaderMatchesReaderPlusDeveloperAndReportsTheFullSize() {
        for (bytes in listOf(bayer, merged())) {
            val img = assertNotNull(RawReader.read(bytes))
            val got = assertNotNull(RawLoader.load(ByteArraySource(bytes), 300, fast = true))
            assertSame(RawDeveloper.develop(img, 300, true), got.image, "loader")
            assertEquals(img.uprightWidth, got.fullWidth); assertEquals(img.uprightHeight, got.fullHeight)
        }
    }

    @Test fun loaderReadsFromAFileChannel() {
        val f = File.createTempFile("merged", ".dng").apply { deleteOnExit() }
        val bytes = merged()
        f.writeBytes(bytes)
        val got = RawFile.open(f).use { assertNotNull(RawLoader.load(it, 4096, fast = false)) }
        assertSame(RawDeveloper.develop(DngReader.read(bytes)!!, 4096), got.image, "file")
        f.delete()
    }

    @Test fun unstreamableLayoutsFallBackToTheWholeFileReaders() {
        // lossless JPEG tiles: DngReader.open declines, RawLoader reads the file whole
        val w = 64; val h = 48
        val samples = IntArray(w * h) { 300 + (it * 37) % 500 }
        val tiles = (0 until 2).map { t ->
            val tile = IntArray(32 * h) { i -> samples[(i / 32) * w + t * 32 + i % 32] }
            LjpegEncoder.encode(tile, 16, h, 2, 12, 1)
        }
        val ifd = TiffBuilder.Ifd().byte(50706, 1, 4, 0, 0).long(256, w).long(257, h).short(258, 12).short(259, 7)
            .short(262, 32803).short(277, 1).short(33421, 2, 2).byte(33422, 0, 1, 1, 2).long(50717, 4095)
            .long(322, 32).long(323, h).blobs(324, 325, tiles)
            .srational(50721, *Mat3.IDENTITY).rational(50728, 0.5, 1.0, 0.7)
        val bytes = TiffBuilder().build(ifd)
        val img = assertNotNull(DngReader.read(bytes))
        assertNull(DngReader.open(ByteArraySource(bytes)))
        val got = assertNotNull(RawLoader.load(ByteArraySource(bytes), 4096, fast = false))
        assertSame(RawDeveloper.develop(img, 4096), got.image, "ljpeg")
        // not a raw file at all
        assertNull(RawLoader.load(ByteArraySource(ByteArray(5000) { it.toByte() }), 512))
    }

    @Test fun metadataBeyondTheFirstReadIsFetched() {
        // a 400 kB DNGPrivateData tag pushes the gain maps and noise profile past the first 256 kB read
        val big = RawFixtures.bayerDng(64, 48, privateData = ByteArray(400_000) { 7 })
        assertTrue(DngReader.metadataEnd(big.copyOf(256 shl 10))!! > 256 shl 10)
        val whole = assertNotNull(DngReader.read(big))
        val streamed = assertNotNull(DngReader.open(ByteArraySource(big)))
        assertEquals(whole.gainMaps.size, streamed.gainMaps.size)
        assertEquals(whole.noiseScale, streamed.noiseScale)
        assertSame(RawDeveloper.develop(whole, 4096), RawDeveloper.develop(streamed, 4096), "late metadata")
    }

    @Test fun corruptStreamedPixelsFailCleanly() {
        val bytes = merged()
        val ifd = Tiff.parse(bytes)!!.ifd0
        val offsets = ifd.longs(273)!!
        val bad = bytes.copyOf()
        // garbage in the middle of the third strip's deflate stream
        for (i in 0 until 64) bad[(offsets[2] + 40 + i).toInt()] = (i * 91).toByte()
        assertNull(DngReader.read(bad))
        assertNull(RawLoader.load(ByteArraySource(bad), 4096, fast = false))
        // truncated file: the strips run past the end
        assertNull(RawLoader.load(ByteArraySource(bytes.copyOf(bytes.size / 2)), 512))
    }

    @Test fun cancellationStopsBetweenBands() {
        var calls = 0
        assertFailsWith<CancellationException> {
            RawLoader.load(ByteArraySource(bayer), 4096, fast = false) { ++calls > 2 }
        }
    }

    @Test fun manyThreadsDoNotChangeTheResult() {
        // the same load repeated concurrently (as when a thumbnail and a preview overlap)
        val bytes = merged(300, 260)
        val ref = bits(RawLoader.load(ByteArraySource(bytes), 4096, fast = false)!!.image)
        val results = (0 until 4).map { java.util.concurrent.CompletableFuture.supplyAsync { bits(RawLoader.load(ByteArraySource(bytes), 4096, fast = false)!!.image) } }
        for (r in results) assertContentEquals(ref, r.get(60, TimeUnit.SECONDS))
    }

    /**
     * Regression: a merged-burst-sized Linear DNG (4064 x 3056, as the camera writes it)
     * must develop to a preview — and at full size for zoom — inside a phone-sized heap.
     * Runs in a separate JVM so the heap cap is real. Direct buffers are capped as well,
     * because on Android they live in the Java heap.
     */
    @Test fun mergedBurstLoadsInABoundedHeap() {
        val dir = createTempDir()
        try {
            assertEquals(0, jvm("1g", "1g", dir.path, "gen").exitValue, "fixture generation failed")
            assertTrue(File(dir, "merged.dng").length() > 40_000_000)
            // preview: 2032 x 1528 output (25 MB) + denoise planes + band buffers
            val preview = jvm("48m", "32m", dir.path, "merged:preview", "1")
            assertEquals(0, preview.exitValue, "preview did not fit: ${preview.output}")
            // zoom: full 4064 x 3056 (99 MB output) + full-size denoise planes
            val full = jvm("96m", "104m", dir.path, "merged:full", "1")
            assertEquals(0, full.exitValue, "full size did not fit: ${full.output}")
            // the previous path (whole file on the heap, whole frame decoded) cannot do the preview in 3x that
            val old = jvm("144m", "104m", dir.path, "merged:preview", "1", impl = "old")
            assertTrue(old.exitValue != 0, "the bound does not discriminate: ${old.output}")
        } finally { dir.deleteRecursively() }
    }

    private class Run(val exitValue: Int, val output: String)

    private fun jvm(heap: String, direct: String, vararg args: String, impl: String = "new"): Run {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(listOf(java, "-Xmx$heap", "-XX:MaxDirectMemorySize=$direct", "-XX:+UseSerialGC",
            "-Dbench.impl=$impl", "-cp", System.getProperty("java.class.path"), LoadBench::class.java.name) + args)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(5, TimeUnit.MINUTES)) { "timed out" }
        println(out.lines().filter { "run " in it || "MB" in it }.joinToString("\n"))
        return Run(p.exitValue(), out.takeLast(2000))
    }

    @Suppress("DEPRECATION")
    private fun createTempDir() = kotlin.io.createTempDir("rawload")
}
