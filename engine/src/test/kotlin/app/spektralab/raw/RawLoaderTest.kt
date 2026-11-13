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

    @Test fun fastLinearPreviewPreservesCurrentResolutionPolicy() {
        val img = assertNotNull(DngReader.read(merged()))
        assertSame(LegacyDeveloper.develop(img, 400, true), RawDeveloper.develop(img, 400, true), "fast dimensions")
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
        val calls = java.util.concurrent.atomic.AtomicInteger()
        assertFailsWith<CancellationException> {
            RawLoader.load(ByteArraySource(bayer), 4096, fast = false, cancelled = { calls.incrementAndGet() > 2 })
        }
    }

    @Test fun manyThreadsDoNotChangeTheResult() {
        // the same load repeated concurrently (as when a thumbnail and a preview overlap)
        val bytes = merged(300, 260)
        val ref = bits(RawLoader.load(ByteArraySource(bytes), 4096, fast = false)!!.image)
        val results = (0 until 4).map { java.util.concurrent.CompletableFuture.supplyAsync { bits(RawLoader.load(ByteArraySource(bytes), 4096, fast = false)!!.image) } }
        for (r in results) assertContentEquals(ref, r.get(60, TimeUnit.SECONDS))
    }

    @Test fun streamedBudgetIncludesScratchAndKeepsTheCallerAllocator() {
        val bytes = merged(1024, 768)
        val img = assertNotNull(DngReader.open(ByteArraySource(bytes)))
        val budget = RawDeveloper.workingBytes(img, 128, true, false)
        val plainPixels = RawDeveloper.workingBytes(img.cropWidth, img.cropHeight, true, 128, true, false)
        assertTrue(budget > plainPixels + RawDeveloper.preparedGainBytes(img))
        val edge = RawDeveloper.longEdgeWithin(img, 4096, true, budget, false)
        assertTrue(RawDeveloper.workingBytes(img, edge, true, false) <= budget)
        var allocated: java.nio.ByteBuffer? = null
        val file = File.createTempFile("mapped-development", ".bin")
        try {
            java.io.RandomAccessFile(file, "rw").use { f ->
                val result = assertNotNull(RawLoader.load(ByteArraySource(bytes), 4096, rawHeapBudget = budget,
                    allocate = { size ->
                        f.setLength(size.toLong())
                        f.channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, size.toLong()).also { allocated = it }
                    }))
                assertTrue(result.image.halfRgba === allocated)
                var calls = 0
                val expected = RawDeveloper.develop(img, edge, true) { size ->
                    calls++
                    java.nio.ByteBuffer.allocateDirect(size)
                }
                assertEquals(1, calls, "existing trailing allocator API")
                assertSame(expected, result.image, "mapped budgeted stream")
                assertTrue(result.image.width > 0 && result.image.height > 0)
            }
        } finally { file.delete() }
        assertFailsWith<IllegalArgumentException> { RawDeveloper.longEdgeWithin(img, 4096, true, 1, false) }
    }

    @Test fun channelSourceHonorsSliceAndRejectsOverflowReads() {
        val bytes = merged()
        val file = File.createTempFile("raw-slice", ".bin")
        file.writeBytes(ByteArray(123) { 55 } + bytes + ByteArray(87) { 66 })
        try {
            java.io.RandomAccessFile(file, "r").use { f ->
                ChannelSource(f.channel, 123, bytes.size.toLong()).use { source ->
                    assertEquals(bytes.size.toLong(), source.size)
                    assertContentEquals(bytes, source.readAll())
                    assertSame(RawLoader.load(ByteArraySource(bytes), 512)!!.image,
                        RawLoader.load(source, 512)!!.image, "provider slice")
                    assertFailsWith<java.io.IOException> { source.read(Long.MAX_VALUE, ByteArray(8)) }
                    assertFailsWith<java.io.IOException> { source.read(source.size - 1, ByteArray(8)) }
                }
            }
        } finally { file.delete() }
    }

    @Test fun cancellationDrainsWorkersAndNeverReadsWholeFileAfterStreamingStarts() {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        val pixelReads = java.util.concurrent.atomic.AtomicInteger()
        val backing = ByteArraySource(bayer)
        val source = object : ByteSource {
            override val size get() = backing.size
            override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
                assertTrue(len < size, "unexpected whole-file fallback")
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                try {
                    backing.read(pos, dst, off, len)
                    if (pos > 0 && pixelReads.incrementAndGet() >= 2) stop.set(true)
                } finally { active.decrementAndGet() }
            }
        }
        assertFailsWith<CancellationException> {
            RawLoader.load(source, 4096, fast = false, cancelled = { stop.get() })
        }
        assertEquals(0, active.get(), "workers outlived the source")
        assertTrue(pixelReads.get() >= 2)
        assertTrue(peak.get() <= RawDeveloper.WORKERS)
        // A cancelled load must leave the shared executor usable.
        assertNotNull(RawLoader.load(ByteArraySource(bayer), 64))
    }

    @Test fun croppedStripStillValidatesItsDeflateTrailer() {
        val bytes = merged()
        val raw = assertNotNull(DngReader.open(ByteArraySource(bytes)))
        val ifd = Tiff.parse(bytes)!!.ifd0
        val offset = ifd.longs(273)!![0]
        val count = ifd.longs(279)!![0]
        bytes[(offset + count - 1).toInt()] = (bytes[(offset + count - 1).toInt()].toInt() xor 0x7f).toByte()
        assertFailsWith<java.util.zip.DataFormatException> { raw.rows!!.read(0, 1, ShortArray(raw.rawWidth * 3)) }
    }

    @Test fun oversizedDeflateStripUsesOnlyTheCappedWholeFileFallback() {
        val w = 2048; val h = 512
        val compressed = ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(ByteArray(w * h * 6)) }
        }.toByteArray()
        val ifd = TiffBuilder.Ifd().byte(50706, 1, 4, 0, 0).long(256, w).long(257, h)
            .short(258, 16, 16, 16).short(259, 8).short(262, 34892).short(277, 3)
            .long(278, h).short(284, 1).short(317, 1).short(339, 1, 1, 1)
            .long(50717, 65535).srational(50721, *Mat3.IDENTITY).rational(50728, 1.0, 1.0, 1.0)
            .blobs(273, 279, listOf(compressed))
        val bytes = TiffBuilder().build(ifd)
        assertNull(DngReader.open(ByteArraySource(bytes)), "six-MiB strip must not become worker scratch")
        val fallback = assertNotNull(RawLoader.open(ByteArraySource(bytes)))
        assertNull(fallback.rows)
        assertEquals(w * h * 3, fallback.data.size)
        assertNull(RawLoader.open(ByteArraySource(bytes), maxWholeFile = bytes.size - 1L))
    }

    @Test fun metadataAndStripLimitsDeclineBeforeLargeAllocations() {
        val bytes = merged()
        val ifd = Tiff.parse(bytes)!!.ifd0
        val bad = bytes.copyOf()
        // A strip count beyond the entire source must be rejected at metadata open.
        java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(ifd[279]!!.dataOffset, Int.MAX_VALUE)
        assertNull(DngReader.open(ByteArraySource(bad)))
        val giant = object : ByteSource {
            override val size = RawLoader.MAX_WHOLE_FILE + 1
            override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) = error("oversized source was read")
        }
        assertNull(RawLoader.open(giant))
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
        val log = File.createTempFile("rawload-process", ".log")
        val p = ProcessBuilder(listOf(java, "-Xmx$heap", "-XX:MaxDirectMemorySize=$direct", "-XX:+UseSerialGC",
            "-Dbench.impl=$impl", "-cp", System.getProperty("java.class.path"), LoadBench::class.java.name) + args)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
                error("bounded-heap child timed out")
            }
            val out = log.readText()
            println(out.lines().filter { "run " in it || "MB" in it }.joinToString("\n"))
            return Run(p.exitValue(), out.takeLast(2000))
        } finally { if (p.isAlive) p.destroyForcibly(); log.delete() }
    }

    @Suppress("DEPRECATION")
    private fun createTempDir() = kotlin.io.createTempDir("rawload")
}
