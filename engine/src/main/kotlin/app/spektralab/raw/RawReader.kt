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

    fun read(bytes: ByteArray): RawImage? = readFormat(bytes)?.second

    /** The image and the name of the [Format] that read it. */
    internal fun readFormat(bytes: ByteArray): Pair<String, RawImage>? {
        for (f in formats) {
            val img = try { f.read(bytes) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            if (img != null) return f.name to img
        }
        return null
    }
}
