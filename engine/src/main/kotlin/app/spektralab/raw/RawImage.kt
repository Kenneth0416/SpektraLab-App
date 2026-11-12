package app.spektralab.raw

/**
 * Colour filter array layout, anchored at raw pixel (0, 0): the colour of raw pixel
 * (row, col) is `colors[(row % height) * width + col % width]`, with 0 = R, 1 = G, 2 = B.
 *
 * A Bayer sensor is a 2x2 pattern; Fuji X-Trans is 6x6. Readers whose metadata is
 * relative to some other origin (DNG: the ActiveArea, most makers: the visible area)
 * use [anchoredAt] to move it to the raw origin.
 */
class CfaPattern(val width: Int, val height: Int, val colors: IntArray) {
    init {
        require(width in 1..MAX_DIM && height in 1..MAX_DIM) { "CFA pattern ${width}x$height" }
        require(colors.size == width * height) { "CFA pattern needs ${width * height} colours" }
    }

    /** Number of positions in one period. */
    val size get() = width * height

    /** Index into [colors] (and any per-position array such as [RawImage.black]) for a raw pixel. */
    fun index(row: Int, col: Int) = Math.floorMod(row, height) * width + Math.floorMod(col, width)

    fun color(row: Int, col: Int) = colors[index(row, col)]

    /**
     * True when the pattern is a 2x2 Bayer mosaic (one R, two G on a diagonal, one B),
     * possibly written out over a larger even period. This is what [RawDeveloper] develops.
     */
    val isBayer: Boolean by lazy {
        if (width % 2 != 0 || height % 2 != 0) return@lazy false
        for (r in 0 until height) for (c in 0 until width) if (colors[r * width + c] != colors[(r % 2) * width + c % 2]) return@lazy false
        val q = intArrayOf(colors[0], colors[1], colors[width], colors[width + 1])
        fun redBlue(a: Int, b: Int) = (a == 0 && b == 2) || (a == 2 && b == 0)
        (q[0] == 1 && q[3] == 1 && redBlue(q[1], q[2])) || (q[1] == 1 && q[2] == 1 && redBlue(q[0], q[3]))
    }

    /**
     * The same physical pattern re-anchored at raw (0, 0), when this one describes the
     * colours relative to raw pixel ([top], [left]) (e.g. a DNG ActiveArea origin).
     */
    fun anchoredAt(top: Int, left: Int): CfaPattern = CfaPattern(width, height, shiftPattern(colors, width, height, top, left))

    /** Per-position black levels from per-colour levels (R, G, B). */
    fun perColor(rgb: FloatArray): FloatArray = FloatArray(size) { rgb[colors[it].coerceIn(0, rgb.size - 1)] }

    override fun toString() = "CfaPattern(${width}x$height, ${colors.joinToString("") { "RGB"[it.coerceIn(0, 2)].toString() }})"

    companion object {
        const val MAX_DIM = 16
        val RGGB = CfaPattern(2, 2, intArrayOf(0, 1, 1, 2))
        val BGGR = CfaPattern(2, 2, intArrayOf(2, 1, 1, 0))
        val GRBG = CfaPattern(2, 2, intArrayOf(1, 0, 2, 1))
        val GBRG = CfaPattern(2, 2, intArrayOf(1, 2, 0, 1))

        /** A pattern from a string such as "RGGB" or the 36 letters of an X-Trans tile. */
        fun parse(width: Int, height: Int, letters: String) =
            CfaPattern(width, height, IntArray(width * height) { "RGB".indexOf(letters[it].uppercaseChar()).also { c -> require(c >= 0) } })

        /**
         * dcraw / LibRaw `filters` word for a 2x2 (or 8x2) Bayer layout, as found in the
         * reference tables, e.g. 0x94949494 = RGGB. Colour 3 (second green) maps to G.
         */
        fun fromDcrawFilters(filters: Int): CfaPattern {
            fun fc(row: Int, col: Int) = (filters ushr ((((row shl 1) and 14) or (col and 1)) shl 1)) and 3
            return CfaPattern(2, 2, IntArray(4) { i -> fc(i / 2, i % 2).let { if (it == 3) 1 else it } })
        }

        /** Re-anchors any per-position table of a [width] x [height] period (see [CfaPattern.anchoredAt]). */
        fun shiftPattern(values: IntArray, width: Int, height: Int, top: Int, left: Int) = IntArray(width * height) { i ->
            values[Math.floorMod(i / width - top, height) * width + Math.floorMod(i % width - left, width)]
        }

        fun shiftPattern(values: FloatArray, width: Int, height: Int, top: Int, left: Int) = FloatArray(width * height) { i ->
            values[Math.floorMod(i / width - top, height) * width + Math.floorMod(i % width - left, width)]
        }
    }
}

/** Lens shading gain map (DNG opcode 9), one per CFA position on phone cameras. */
class GainMap(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val rowPitch: Int, val colPitch: Int,
    val pointsV: Int, val pointsH: Int,
    val spacingV: Double, val spacingH: Double, val originV: Double, val originH: Double,
    val gains: FloatArray,
    val plane: Int = 0,
) {
    /** Gain at raw pixel (x, y) of an image [width] x [height] (bilinear, clamped). */
    fun gain(x: Int, y: Int, width: Int, height: Int): Float {
        val v = ((y.toDouble() / height - originV) / spacingV).coerceIn(0.0, (pointsV - 1).toDouble())
        val h = ((x.toDouble() / width - originH) / spacingH).coerceIn(0.0, (pointsH - 1).toDouble())
        val v0 = v.toInt().coerceAtMost(pointsV - 2).coerceAtLeast(0)
        val h0 = h.toInt().coerceAtMost(pointsH - 2).coerceAtLeast(0)
        val v1 = minOf(v0 + 1, pointsV - 1)
        val h1 = minOf(h0 + 1, pointsH - 1)
        val fv = (v - v0).toFloat()
        val fh = (h - h0).toFloat()
        val a = gains[v0 * pointsH + h0] * (1 - fh) + gains[v0 * pointsH + h1] * fh
        val b = gains[v1 * pointsH + h0] * (1 - fh) + gains[v1 * pointsH + h1] * fh
        return a * (1 - fv) + b * fv
    }

    fun covers(x: Int, y: Int) = y >= top && y < bottom && x >= left && x < right &&
        (y - top) % rowPitch == 0 && (x - left) % colPitch == 0
}

/**
 * A mosaiced raw frame from any camera, with the metadata needed to develop it to
 * scene-linear sRGB. Format readers fill this in; [RawDeveloper] develops it.
 *
 * Pixel pipeline (per raw sample of colour c = [cfa] colour at that pixel):
 * `v = clamp((raw - black) / (white - black), 0, 1)`, `v = min(v * wb[c], 1)`, optional gain
 * map, demosaic, then `srgb = camToSrgb * (r, g, b)`. Build [wb] and [camToSrgb] with
 * [RawColor]; a neutral surface whose green raw value reaches [white] then develops to 1.0.
 */
class RawImage(
    /** Full stored raw frame size, including any masked / optical-black borders. */
    val rawWidth: Int,
    val rawHeight: Int,
    /** Sensor values, row-major [rawWidth] x [rawHeight], unsigned 16 bit (read with `and 0xffff`). */
    val data: ShortArray,
    /** CFA layout, anchored at raw (0, 0). */
    val cfa: CfaPattern,
    /** Black level per CFA position (size [CfaPattern.size], indexed by [CfaPattern.index]). */
    val black: FloatArray,
    /**
     * Saturation level in the same units as [data], from metadata only, never from the picture's own
     * data (no LibRaw adjust_maximum), so a camera's files all develop with the same exposure scale.
     * DNG: WhiteLevel. Canon (CR2, CR3): the ColorData SpecularWhiteLevel, since LibRaw's `maximum`
     * (colour table / bit depth) is above the clip point at many ISOs. Panasonic RawFormat 6 / 7: the
     * largest linearity limit. Everything else: LibRaw's `maximum` (colour-table value, else the
     * format's white tag or bit depth), which is what LibRaw renders with and what the real Nikon,
     * Sony and Fuji samples' clipped highlights reach; their lower recorded linearity limits (LibRaw
     * `linear_max`) are not used.
     */
    val white: Float,
    /**
     * Region to output, raw pixel coordinates, any CFA phase: the camera JPEG area when the file
     * records one (DNG DefaultCrop, the maker inset crops LibRaw reports as `raw_inset_crops[0]`:
     * Canon SensorInfo, Nikon 0x45, Sony DefaultCrop, Olympus ImageProcessing crop, Pentax 0x38/0x39,
     * Panasonic sensor borders / 0x2f..0x32, Fuji), else LibRaw's visible area. LibRaw's even-origin
     * (Bayer) and period-of-6 (X-Trans) alignment is applied.
     */
    val cropX: Int, val cropY: Int, val cropWidth: Int, val cropHeight: Int,
    /** White balance gains (R, G, B), green = 1, from the camera's as-shot multipliers, applied to black-subtracted, normalised camera RGB. */
    val wb: FloatArray,
    /** White-balanced camera RGB -> linear sRGB (D65), row-major 3x3. */
    val camToSrgb: FloatArray,
    /** EXIF / TIFF orientation 1..8 (mirrored ones included). */
    val orientation: Int = 1,
    /** Exposure offset in EV the camera wants applied (DNG BaselineExposure). */
    val baselineExposure: Float = 0f,
    val gainMaps: List<GainMap> = emptyList(),
    val make: String = "",
    val model: String = "",
    val noiseScale: Float? = null,
    val noiseOffset: Float = 0f,
    /** Interleaved camera RGB, three samples per pixel; CFA unused. */
    val linear: Boolean = false,
    val tags: Map<Int, DngTag> = emptyMap(),
    /**
     * Streamed pixels: when set, [data] is empty and the samples are decoded on demand
     * through these rows ([DngReader.open], for [RawDeveloper] only). Format readers
     * registered with [RawReader] leave it null and fill [data].
     */
    val rows: RawRows? = null,
) {
    init {
        require(rawWidth > 0 && rawHeight > 0 && (rows != null || data.size.toLong() >= rawWidth.toLong() * rawHeight * (if (linear) 3 else 1))) { "raw size ${rawWidth}x$rawHeight vs ${data.size} samples" }
        require(black.size == cfa.size) { "black has ${black.size} values for a ${cfa.size}-position CFA" }
        require(cropX >= 0 && cropY >= 0 && cropWidth > 0 && cropHeight > 0 &&
            cropX + cropWidth <= rawWidth && cropY + cropHeight <= rawHeight) { "crop $cropX,$cropY ${cropWidth}x$cropHeight outside ${rawWidth}x$rawHeight" }
        require(wb.size == 3 && camToSrgb.size == 9) { "wb needs 3 and camToSrgb 9 values" }
        require(white > black.max()) { "white $white not above black" }
        require(orientation in 1..8) { "orientation $orientation" }
    }

    /** Bayer cell at the crop origin, shared by CPU and GPU burst paths. */
    val cropCfa = IntArray(4) { cfa.color(cropY + it / 2, cropX + it % 2) }
    val cropBlack = FloatArray(4) { blackAt(cropY + it / 2, cropX + it % 2) }

    /** Black level at a raw pixel. */
    fun blackAt(row: Int, col: Int) = black[cfa.index(row, col)]

    private val swapsAxes get() = orientation >= 5
    /** Size after applying [orientation]. */
    val uprightWidth get() = if (swapsAxes) cropHeight else cropWidth
    val uprightHeight get() = if (swapsAxes) cropWidth else cropHeight
}

/**
 * Per-decode mobile resource limits, checked before allocating sensor buffers.
 * 64M unsigned-short samples cap one decoded plane/frame at 128 MB; callers must
 * separately budget the input bytes, development buffers and concurrent frames.
 * Larger files are unsupported rather than relying on OutOfMemoryError recovery.
 */
internal object RawDecodeLimits {
    const val MAX_SAMPLES = 64_000_000L
    const val MAX_SEGMENTS = 16_384
}

/** A preserved TIFF metadata value for writing a derived DNG. */
class DngTag(val type: Int, val values: DoubleArray, val ascii: ByteArray? = null)

/** The previous name, from when only DNG was read. */
typealias DngImage = RawImage
