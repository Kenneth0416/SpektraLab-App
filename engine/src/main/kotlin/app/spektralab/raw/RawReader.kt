package app.spektralab.raw

/**
 * Single entry point for raw files: tries each registered format reader in order and
 * returns the first image one of them produces, or null (caller falls back to the
 * system decoder).
 *
 * The result may still be a CFA that [RawDeveloper] cannot develop (check
 * [RawDeveloper.supports]; X-Trans needs its own demosaic).
 *
 * ## Adding a format
 * Write `object XxxReader { fun read(bytes: ByteArray): RawImage? }` in its own file
 * (`Cr2.kt`, `Nef.kt`, ...) and add `XxxReader::read` to [readers]. The reader must:
 * - return null fast when the file is not its format (magic bytes, then `Make`);
 *   exceptions are caught here and count as "not mine", but don't rely on that for speed;
 * - parse TIFF structure with [Tiff.parse] (`allIfds()`, `exif`, `makerNote()`), and
 *   decode pixels with [LosslessJpeg], [HuffmanTable] + [MsbBitPump] / [LsbBitPump] /
 *   [MsbWordBitPump], or [Unpack] into a `ShortArray(rawWidth * rawHeight)`;
 * - describe the frame in raw coordinates: [CfaPattern] anchored at raw (0, 0) (use
 *   [CfaPattern.anchoredAt] if the maker gives it relative to the visible area,
 *   [CfaPattern.fromDcrawFilters] for a dcraw `filters` word), `black` per CFA position
 *   ([CfaPattern.perColor] for per-colour levels), `white`, and the visible crop
 *   (any position: the developer handles any CFA phase);
 * - colour: `RawColor.fromXyzToCamera(xyzToCamera, asShotMultipliers)` with the
 *   adobe_coeff-style matrix and the maker-note WB, giving `wb` and `camToSrgb`;
 * - pass the EXIF orientation (1..8), `make` and `model`.
 */
object RawReader {
    /** A registered format: [name] is the usual file extension, upper case. */
    internal class Format(val name: String, val read: (ByteArray) -> RawImage?)

    /**
     * Registered readers. To add a format, add one line here.
     *
     * Order: readers whose files are told apart by a cheap, unique magic come first (CR3 `ftypcrx `,
     * RAF `FUJIFILM`, RW2 TIFF magic 0x55, ORF TIFF magic `RO` / `RS`, CR2 `CR\2` at byte 8), then
     * DNG (any TIFF with DNGVersion), then the makers whose raws are plain TIFF (magic 42) told apart
     * only by Make. Every maker reader also refuses files with a DNGVersion tag, so a camera's own
     * DNG (Pentax, Leica, Samsung / Ricoh phones ...) is always read as DNG, and the DNG reader
     * refuses anything without DNGVersion; the order is therefore for speed, not for correctness
     * (checked by RawReaderTest: every sample and fixture is claimed by its own reader only).
     */
    internal val formats: List<Format> = listOf(
        Format("CR3", Cr3Reader::read),
        Format("RAF", RafReader::read),
        Format("RW2", Rw2Reader::read),
        Format("ORF", OrfReader::read),
        Format("CR2", Cr2Reader::read),
        Format("DNG", DngReader::read),
        Format("NEF", NefReader::read),
        Format("ARW", ArwReader::read),
        Format("PEF", PefReader::read),
        Format("SRW", SrwReader::read),
    )

    fun read(bytes: ByteArray): RawImage? {
        // The decoded frame is allocated on top of [bytes]. Refuse anything a phone heap
        // cannot hold twice; readers still cap the sensor buffer at [RawDecodeLimits.MAX_SAMPLES].
        if (!withinBudget(bytes.size.toLong())) return null
        return readFormat(bytes)?.second
    }

    /** True when a whole-file read of [size] bytes stays inside [maxBytes] and [RawLoader.MAX_WHOLE_FILE]. */
    internal fun withinBudget(size: Long, maxBytes: Long = RawLoader.MAX_WHOLE_FILE): Boolean =
        size in 0..minOf(maxBytes.coerceAtLeast(0L), RawLoader.MAX_WHOLE_FILE)

    /** Bytes [mayBeRaw] needs from the start of a file. */
    const val SNIFF_BYTES = 16

    /**
     * True when the first bytes of a file ([SNIFF_BYTES] are enough) have the container magic of a
     * registered format: TIFF (DNG, NEF, ARW, PEF, SRW, CR2), RW2 (`IIU`), ORF (`IIRO` / `IIRS` /
     * `MMOR`), RAF (`FUJIFILM`) or CR3 (ISO base media `ftyp` brand `crx `). For files whose name and
     * MIME type say nothing (document providers often report `application/octet-stream`): a true
     * result only means [read] is worth trying; plain TIFF images pass too.
     */
    fun mayBeRaw(head: ByteArray): Boolean {
        fun at(i: Int, s: String) = head.size >= i + s.length && s.indices.all { head[i + it] == s[it].code.toByte() }
        return at(0, "II*\u0000") || at(0, "MM\u0000*") || at(0, "IIU\u0000") || at(0, "IIRO") || at(0, "IIRS") ||
            at(0, "MMOR") || at(0, "FUJIFILM") || (at(4, "ftyp") && at(8, "crx "))
    }

    /** The image and the name of the [Format] that read it. */
    internal fun readFormat(bytes: ByteArray): Pair<String, RawImage>? {
        for (f in formats) {
            val img = try { f.read(bytes) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            if (img != null) return f.name to img
        }
        return null
    }
}
