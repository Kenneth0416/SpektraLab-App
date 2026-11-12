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
    /** Registered readers, most specific first. To add a format, add one line here. */
    private val readers: List<(ByteArray) -> RawImage?> = listOf(
        DngReader::read,
    )

    fun read(bytes: ByteArray): RawImage? {
        for (r in readers) {
            val img = try { r(bytes) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            if (img != null) return img
        }
        return null
    }
}
