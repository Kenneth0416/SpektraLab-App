package app.spektralab.raw

/**
 * TIFF / EXIF structure parser shared by DNG and the TIFF-based raw formats (CR2, NEF,
 * ARW, ORF, RW2, PEF, SRW, the TIFF blocks inside CR3 and RAF, ...).
 *
 * Everything is bounds-checked: parsing never throws, never follows a loop of IFD
 * pointers forever and never allocates more than the file could hold. An entry whose
 * data runs past the end of the file is truncated to what is there.
 *
 * Offsets: [TiffEntry.dataOffset] and [TiffIfd.offset] are absolute positions in
 * [Tiff.bytes]. Offsets stored *in* the file are relative to the IFD's [TiffIfd.base]
 * (the TIFF header position for normal IFDs; maker notes often use their own).
 */
class Tiff private constructor(
    val bytes: ByteArray,
    /** Byte order of the main header. */
    val littleEndian: Boolean,
    /** Absolute position of the TIFF header ("II*\0" / "MM\0*"); file offsets are relative to it. */
    val base: Int,
    /** Header magic: 42 for TIFF, 0x4F52 / 0x5352 for ORF, 0x55 for RW2. */
    val magic: Int,
    /** The main IFD chain (IFD0, IFD1, ...). */
    val ifds: List<TiffIfd>,
) {
    val ifd0: TiffIfd get() = ifds[0]

    /** EXIF IFD of IFD0, if any. */
    val exif: TiffIfd? get() = ifd0.subIfd(TiffTags.EXIF_IFD)

    /** Every IFD in the main chain plus their SubIFDs (recursively), depth-first. */
    fun allIfds(): List<TiffIfd> {
        val out = ArrayList<TiffIfd>()
        fun walk(ifd: TiffIfd, depth: Int) {
            out += ifd
            if (depth < 4) ifd.subIfds().forEach { walk(it, depth + 1) }
        }
        ifds.forEach { walk(it, 0) }
        return out
    }

    /** Parses an IFD at [absOffset] with the given base and byte order (e.g. inside a maker note). */
    fun ifdAt(absOffset: Int, base: Int = this.base, littleEndian: Boolean = this.littleEndian): TiffIfd? =
        TiffIfd.parse(bytes, absOffset, base, littleEndian)

    /** The EXIF maker note parsed as an IFD, handling the per-maker header quirks. */
    fun makerNote(): MakerNote? = exif?.let { MakerNote.parse(this, it) } ?: MakerNote.parse(this, ifd0)

    companion object {
        /** Magic numbers accepted by default. */
        val KNOWN_MAGIC = setOf(42, 0x4F52, 0x5352, 0x55)

        /**
         * Parses a TIFF header at [offset] and its IFD chain. Returns null when there is no
         * valid header or IFD0 cannot be read. [magic] restricts the accepted header magic.
         */
        fun parse(bytes: ByteArray, offset: Int = 0, magic: Set<Int> = KNOWN_MAGIC, maxIfds: Int = 16): Tiff? {
            if (offset < 0 || offset.toLong() + 8 > bytes.size) return null
            val le = when {
                bytes[offset] == 'I'.code.toByte() && bytes[offset + 1] == 'I'.code.toByte() -> true
                bytes[offset] == 'M'.code.toByte() && bytes[offset + 1] == 'M'.code.toByte() -> false
                else -> return null
            }
            val m = Bytes.u16(bytes, offset + 2, le)
            if (m !in magic) return null
            val chain = ArrayList<TiffIfd>()
            val seen = HashSet<Int>()
            var rel = Bytes.u32(bytes, offset + 4, le)
            while (rel != 0L && chain.size < maxIfds) {
                val abs = offset + rel
                if (abs > Int.MAX_VALUE || !seen.add(abs.toInt())) break
                val ifd = TiffIfd.parse(bytes, abs.toInt(), offset, le) ?: break
                chain += ifd
                rel = ifd.next
            }
            if (chain.isEmpty()) return null
            return Tiff(bytes, le, offset, m, chain)
        }
    }
}

/** One IFD entry. [count] is already clamped to the data that is actually in the file. */
class TiffEntry(val tag: Int, val type: Int, val count: Int, val dataOffset: Int) {
    val byteCount get() = count * TiffIfd.typeSize(type)
}

/** One image file directory. */
class TiffIfd private constructor(
    val bytes: ByteArray,
    /** Absolute position of the IFD. */
    val offset: Int,
    /** Absolute position that offsets inside this IFD are relative to. */
    val base: Int,
    val littleEndian: Boolean,
    val entries: Map<Int, TiffEntry>,
    /** Next-IFD pointer as stored (relative to [base]), 0 for none. */
    val next: Long,
) {
    operator fun contains(tag: Int) = tag in entries
    operator fun get(tag: Int): TiffEntry? = entries[tag]

    /** The [i]th value of a numeric tag converted to Double (rationals divided). */
    fun value(e: TiffEntry, i: Int): Double {
        val p = e.dataOffset + i * typeSize(e.type)
        val b = bytes; val le = littleEndian
        return when (e.type) {
            1, 2, 7 -> (b[p].toInt() and 0xff).toDouble()
            6 -> b[p].toDouble()
            3 -> Bytes.u16(b, p, le).toDouble()
            8 -> Bytes.u16(b, p, le).toShort().toDouble()
            4, 13 -> Bytes.u32(b, p, le).toDouble()
            9 -> Bytes.u32(b, p, le).toInt().toDouble()
            5 -> Bytes.u32(b, p, le).toDouble() / Bytes.u32(b, p + 4, le).coerceAtLeast(1)
            10 -> Bytes.u32(b, p, le).toInt().toDouble() / Bytes.u32(b, p + 4, le).toInt().let { if (it == 0) 1 else it }
            11 -> Float.fromBits(Bytes.u32(b, p, le).toInt()).toDouble()
            12 -> Double.fromBits(Bytes.u64(b, p, le))
            16 -> Bytes.u64(b, p, le).toDouble()
            17 -> Bytes.u64(b, p, le).toDouble()
            else -> 0.0
        }
    }

    /** All values of a numeric tag, at most [max] of them; null when absent or empty. */
    fun doubles(tag: Int, max: Int = 1 shl 20): DoubleArray? {
        val e = entries[tag] ?: return null
        if (e.count == 0) return null
        return DoubleArray(minOf(e.count, max)) { value(e, it) }
    }

    fun ints(tag: Int, max: Int = 1 shl 20): IntArray? {
        val e = entries[tag] ?: return null
        if (e.count == 0) return null
        return IntArray(minOf(e.count, max)) { value(e, it).toLong().toInt() }
    }

    /** Unsigned 32-bit values (offsets, byte counts) as Long. */
    fun longs(tag: Int, max: Int = 1 shl 20): LongArray? {
        val e = entries[tag] ?: return null
        if (e.count == 0) return null
        return LongArray(minOf(e.count, max)) { value(e, it).toLong() }
    }

    fun int(tag: Int): Int? = entries[tag]?.takeIf { it.count > 0 }?.let { value(it, 0).toLong().toInt() }
    fun int(tag: Int, default: Int): Int = int(tag) ?: default
    fun double(tag: Int): Double? = entries[tag]?.takeIf { it.count > 0 }?.let { value(it, 0) }

    /** ASCII value up to the first NUL, trimmed. */
    fun string(tag: Int): String? {
        val e = entries[tag] ?: return null
        var n = 0
        while (n < e.count && bytes[e.dataOffset + n] != 0.toByte()) n++
        return String(bytes, e.dataOffset, n, Charsets.ISO_8859_1).trim()
    }

    /** Raw value bytes of a tag (copy). */
    fun bytes(tag: Int): ByteArray? = entries[tag]?.let { bytes.copyOfRange(it.dataOffset, it.dataOffset + it.byteCount) }

    /**
     * IFDs pointed to by [tag] (SubIFDs by default; also EXIF 34665, GPS 34853, Interop
     * 40965). Offsets are relative to this IFD's base and byte order is inherited.
     */
    fun subIfds(tag: Int = TiffTags.SUB_IFDS): List<TiffIfd> {
        val e = entries[tag] ?: return emptyList()
        val out = ArrayList<TiffIfd>()
        for (i in 0 until minOf(e.count, 64)) {
            val abs = base + value(e, i).toLong()
            if (abs == offset.toLong()) continue
            if (abs in 0..Int.MAX_VALUE) parse(bytes, abs.toInt(), base, littleEndian)?.let { out += it }
        }
        return out
    }

    fun subIfd(tag: Int): TiffIfd? = subIfds(tag).firstOrNull()

    override fun toString() = "TiffIfd(@$offset, ${entries.size} entries)"

    companion object {
        fun typeSize(type: Int) = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11, 13 -> 4; 5, 10, 12, 16, 17, 18 -> 8; else -> 1 }

        /** Parses one IFD at absolute [offset]; offsets stored in it are relative to [base]. */
        fun parse(bytes: ByteArray, offset: Int, base: Int, littleEndian: Boolean): TiffIfd? {
            if (offset < 0 || offset.toLong() + 2 > bytes.size) return null
            val n = Bytes.u16(bytes, offset, littleEndian)
            val fits = ((bytes.size - offset - 2) / 12).coerceAtLeast(0)
            val count = minOf(n, fits)
            if (count == 0) return null
            val map = HashMap<Int, TiffEntry>(count * 2)
            for (i in 0 until count) {
                val p = offset + 2 + i * 12
                val tag = Bytes.u16(bytes, p, littleEndian)
                val type = Bytes.u16(bytes, p + 2, littleEndian)
                if (type == 0 || type > 18) continue
                val cnt = Bytes.u32(bytes, p + 4, littleEndian)
                val size = typeSize(type)
                val total = cnt * size
                val data: Long = if (total <= 4) p + 8L else base + Bytes.u32(bytes, p + 8, littleEndian)
                val avail = if (data < 0 || data >= bytes.size) 0L else (bytes.size - data) / size
                val clamped = minOf(cnt, avail, Int.MAX_VALUE.toLong() / 8).toInt()
                if (tag !in map) map[tag] = TiffEntry(tag, type, clamped, if (clamped == 0) 0 else data.toInt())
            }
            val nextPos = offset + 2L + n * 12L
            val next = if (n == count && nextPos + 4 <= bytes.size) Bytes.u32(bytes, nextPos.toInt(), littleEndian) else 0L
            return TiffIfd(bytes, offset, base, littleEndian, map, next)
        }
    }
}

/**
 * A maker note parsed as an IFD. [kind] names the header variant that was recognised
 * ("nikon", "olympus", "fuji", "pentax", "sony", "panasonic", "plain", ...). [start] is
 * the absolute position of the maker note data, for formats that need the raw bytes.
 */
class MakerNote(val kind: String, val ifd: TiffIfd, val start: Int, val length: Int) {
    companion object {
        /** Parses the maker note (tag 37500) of [owner], normally the EXIF IFD. */
        fun parse(tiff: Tiff, owner: TiffIfd): MakerNote? {
            val e = owner[TiffTags.MAKER_NOTE] ?: return null
            return parseAt(tiff, e.dataOffset, e.byteCount)
        }

        /** Parses maker note data found at absolute [start]. */
        fun parseAt(tiff: Tiff, start: Int, length: Int): MakerNote? {
            val b = tiff.bytes
            if (length < 8 || start < 0 || start.toLong() + length > b.size) return null
            fun has(s: String, at: Int = 0) = at + s.length <= length && (s.indices).all { b[start + at + it] == s[it].code.toByte() }
            fun order(at: Int): Boolean? = when { has("II", at) -> true; has("MM", at) -> false; else -> null }
            fun ifd(kind: String, at: Int, base: Int, le: Boolean) =
                TiffIfd.parse(b, start + at, base, le)?.let { MakerNote(kind, it, start, length) }
            return when {
                // "Nikon\0" 02 xx 00 00, then a complete TIFF header; offsets relative to it
                has("Nikon\u0000") && length > 18 && b[start + 6].toInt() == 2 -> {
                    val inner = Tiff.parse(b, start + 10, setOf(42)) ?: return null
                    MakerNote("nikon", inner.ifd0, start, length)
                }
                has("Nikon\u0000") -> ifd("nikon1", 8, tiff.base, tiff.littleEndian)
                // new Olympus: "OLYMPUS\0" + byte order + version, offsets relative to the note
                has("OLYMPUS\u0000") -> ifd("olympus", 12, start, order(8) ?: tiff.littleEndian)
                has("OM SYSTEM\u0000") -> ifd("olympus", 16, start, order(12) ?: tiff.littleEndian)
                has("OLYMP\u0000") || has("EPSON\u0000") || has("MINOL") -> ifd("olympus-old", 8, tiff.base, tiff.littleEndian)
                // Fuji: always little endian, IFD offset stored at +8, offsets relative to the note
                has("FUJIFILM") || has("GENERALE") -> ifd("fuji", Bytes.u32(b, start + 8, true).toInt(), start, true)
                has("AOC\u0000") -> order(4)?.let { ifd("pentax", 6, tiff.base, it) } ?: ifd("pentax", 4, tiff.base, tiff.littleEndian)
                has("PENTAX \u0000") -> ifd("pentax", 10, start, order(8) ?: tiff.littleEndian)
                has("SONY DSC \u0000") || has("SONY CAM \u0000") || has("SONY MOBILE") -> ifd("sony", 12, tiff.base, tiff.littleEndian)
                has("Panasonic\u0000") -> ifd("panasonic", 12, tiff.base, tiff.littleEndian)
                has("LEICA\u0000") -> ifd("leica", 8, tiff.base, tiff.littleEndian)
                has("Apple iOS\u0000") -> ifd("apple", 14, start, false)
                // Canon, Samsung, Kodak and many others: a bare IFD with main-file offsets
                else -> ifd("plain", 0, tiff.base, tiff.littleEndian)
            }
        }
    }
}

/** Tag numbers used across readers. */
object TiffTags {
    const val NEW_SUBFILE_TYPE = 254
    const val IMAGE_WIDTH = 256
    const val IMAGE_LENGTH = 257
    const val BITS_PER_SAMPLE = 258
    const val COMPRESSION = 259
    const val PHOTOMETRIC = 262
    const val MAKE = 271
    const val MODEL = 272
    const val STRIP_OFFSETS = 273
    const val ORIENTATION = 274
    const val SAMPLES_PER_PIXEL = 277
    const val ROWS_PER_STRIP = 278
    const val STRIP_BYTE_COUNTS = 279
    const val PLANAR_CONFIG = 284
    const val TILE_WIDTH = 322
    const val TILE_LENGTH = 323
    const val TILE_OFFSETS = 324
    const val TILE_BYTE_COUNTS = 325
    const val SUB_IFDS = 330
    const val JPEG_INTERCHANGE = 513
    const val JPEG_INTERCHANGE_LENGTH = 514
    const val CFA_REPEAT_PATTERN_DIM = 33421
    const val CFA_PATTERN = 33422
    const val EXIF_IFD = 34665
    const val GPS_IFD = 34853
    const val MAKER_NOTE = 37500
    const val EXIF_CFA_PATTERN = 41730

    const val DNG_VERSION = 50706
    const val UNIQUE_CAMERA_MODEL = 50708
    const val CFA_PLANE_COLOR = 50710
    const val CFA_LAYOUT = 50711
    const val LINEARIZATION_TABLE = 50712
    const val BLACK_LEVEL_REPEAT_DIM = 50713
    const val BLACK_LEVEL = 50714
    const val BLACK_LEVEL_DELTA_H = 50715
    const val BLACK_LEVEL_DELTA_V = 50716
    const val WHITE_LEVEL = 50717
    const val DEFAULT_CROP_ORIGIN = 50719
    const val DEFAULT_CROP_SIZE = 50720
    const val COLOR_MATRIX_1 = 50721
    const val COLOR_MATRIX_2 = 50722
    const val CAMERA_CALIBRATION_1 = 50723
    const val CAMERA_CALIBRATION_2 = 50724
    const val ANALOG_BALANCE = 50727
    const val AS_SHOT_NEUTRAL = 50728
    const val AS_SHOT_WHITE_XY = 50729
    const val BASELINE_EXPOSURE = 50730
    const val CALIBRATION_ILLUMINANT_1 = 50778
    const val CALIBRATION_ILLUMINANT_2 = 50779
    const val ACTIVE_AREA = 50829
    const val FORWARD_MATRIX_1 = 50964
    const val FORWARD_MATRIX_2 = 50965
    const val OPCODE_LIST_1 = 51008
    const val OPCODE_LIST_2 = 51009
    const val OPCODE_LIST_3 = 51022
}
