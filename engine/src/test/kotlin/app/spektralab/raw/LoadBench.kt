package app.spektralab.raw

import java.io.File
import java.lang.management.ManagementFactory

/**
 * Load-path benchmark, run outside Gradle so the heap can be capped like a phone's:
 *
 *     java -Xmx256m -cp <engine classes + test classes + kotlin-stdlib> \
 *         app.spektralab.raw.LoadBench <dir> gen | <dir> <case> [repeats]
 *
 * `gen` writes merged.dng (merged-burst Linear DNG) and bayer.dng (12 MP DngCreator-like)
 * into <dir>. Cases: `<file>:<mode>` with file merged|bayer and mode preview (2032 edge,
 * fast), full (4096 edge, full quality) or thumb (512 edge, fast).
 * `-Dbench.impl=` new (default: [RawLoader] streamed from the file), old (whole file read,
 * [RawReader.read], [LegacyDeveloper]), phases / newphases (the same, stage by stage).
 * Reports wall time, allocated bytes (all threads), sampled peak heap and direct buffers;
 * with -XX:+UseSerialGC the sampled heap peak is close to the live peak.
 */
object LoadBench {
    @JvmStatic fun main(args: Array<String>) {
        val dir = File(args[0])
        if (args[1] == "gen") {
            dir.mkdirs()
            File(dir, "merged.dng").outputStream().buffered(1 shl 16).use { RawFixtures.writeMerged(it) }
            File(dir, "bayer.dng").writeBytes(RawFixtures.bayerDng())
            for (f in listOf("merged.dng", "bayer.dng")) println("$f: ${File(dir, f).length() / 1e6} MB")
            return
        }
        val (name, mode) = args[1].split(":")
        val file = File(dir, "$name.dng")
        val repeats = args.getOrNull(2)?.toInt() ?: 3
        val (edge, fast) = when (mode) { "preview" -> 2032 to true; "full" -> 4096 to false; else -> 512 to true }
        val impl = System.getProperty("bench.impl", "new")
        repeat(repeats) { run ->
            System.gc(); Thread.sleep(100)
            val sampler = Sampler().apply { start() }
            val alloc0 = allocated()
            val t0 = System.nanoTime()
            val result = load(file, edge, fast, impl)
            val t1 = System.nanoTime()
            sampler.done = true; sampler.join()
            println("%-14s %-4s run %d: %6.0f ms  out %dx%d  alloc %5.0f MB  peak heap %5.0f MB  peak direct %4.0f MB  gc %d ms".format(
                args[1], impl, run, (t1 - t0) / 1e6, result.width, result.height, (allocated() - alloc0) / 1e6,
                sampler.peakHeap / 1e6, sampler.peakDirect / 1e6, gcMillis()))
        }
    }

    /** "old": what the app did before (read the whole file, decode everything, develop). */
    private fun load(file: File, edge: Int, fast: Boolean, impl: String): LinearImage = when (impl) {
        "old" -> {
            val bytes = file.inputStream().use { it.readBytes() }
            val img = requireNotNull(RawReader.read(bytes))
            LegacyDeveloper.develop(img, edge, fast)
        }
        "phases" -> {
            // the old load, stage by stage (single-threaded developer)
            val bytes = t("readBytes") { file.inputStream().use { it.readBytes() } }
            val img = t("decode") { requireNotNull(RawReader.read(bytes)) }
            val out = t("develop") { LegacyDeveloper.develop(img, edge, fast, RawDeveloper.Options(chromaDenoise = false)) }
            t("denoise") { LegacyChromaDenoise.apply(out, img, 1f) }
            out
        }
        "newphases" -> RawFile.open(file).use { src ->
            val img = t("open") { requireNotNull(RawLoader.open(src)) }
            t("decode") {
                val rows = img.rows!!
                RawDeveloper.bands(img.rawHeight, 1, rows.segmentRows, null) { a, b ->
                    rows.read(a, b, ShortArray((b - a) * img.rawWidth * (if (img.linear) 3 else 1)))
                }
            }
            val out = t("develop") { RawDeveloper.develop(img, edge, fast, RawDeveloper.Options(chromaDenoise = false)) }
            t("denoise") { ChromaDenoise.apply(out, img, if (img.linear && fast) 4f else 1f) }
            out
        }
        else -> RawFile.open(file).use { requireNotNull(RawLoader.load(it, edge, fast)).image }
    }

    private fun <T> t(name: String, f: () -> T): T {
        val a = System.nanoTime()
        return f().also { println("   %-10s %6.0f ms".format(name, (System.nanoTime() - a) / 1e6)) }
    }

    private fun allocated(): Long {
        val b = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return b.getThreadAllocatedBytes(b.allThreadIds).filter { it > 0 }.sum()
    }

    private fun gcMillis() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }

    private class Sampler : Thread() {
        @Volatile var done = false
        var peakHeap = 0L; var peakDirect = 0L
        init { isDaemon = true }
        override fun run() {
            val mem = ManagementFactory.getMemoryMXBean()
            val direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java).first { it.name == "direct" }
            while (!done) {
                peakHeap = maxOf(peakHeap, mem.heapMemoryUsage.used)
                peakDirect = maxOf(peakDirect, direct.memoryUsed)
                sleep(1)
            }
        }
    }
}
