package app.spektralab.raw

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Writes a demosaiced raw image as a Linear DNG (PhotometricInterpretation LinearRaw,
 * camera RGB as 16-bit floating point, deflate with the floating point predictor — the
 * layout the DNG specification defines for compressed linear data). The colour calibration of the
 * source frame is carried over, so any raw developer — including [RawDeveloper] — can
 * still white-balance and convert it; it remains an "original" that can be re-developed.
 */
object DngWriter {
    private class Tag(val id: Int, val type: Int, val count: Int, val data: ByteArray)

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun shorts(id: Int, vararg v: Int) = Tag(id, 3, v.size, le(v.size * 2).apply { v.forEach { putShort(it.toShort()) } }.array())
    private fun longs(id: Int, vararg v: Int) = Tag(id, 4, v.size, le(v.size * 4).apply { v.forEach { putInt(it) } }.array())
    private fun bytes(id: Int, vararg v: Int) = Tag(id, 1, v.size, ByteArray(v.size) { v[it].toByte() })
    private fun ascii(id: Int, text: String) = (text.toByteArray(Charsets.US_ASCII) + 0).let { Tag(id, 2, it.size, it) }
    private fun srationals(id: Int, v: DoubleArray) = Tag(id, 10, v.size,
        le(v.size * 8).apply { v.forEach { putInt(Math.round(it * 1_000_000).toInt()); putInt(1_000_000) } }.array())
    private fun rationals(id: Int, v: DoubleArray) = Tag(id, 5, v.size,
        le(v.size * 8).apply { v.forEach { putInt(Math.round(it * 1_000_000).toInt()); putInt(1_000_000) } }.array())
    private fun doubles(id: Int, v: DoubleArray) = Tag(id, 12, v.size, le(v.size * 8).apply { v.forEach { putDouble(it) } }.array())

    /**
     * @param rgb interleaved camera RGB, [width] x [height] x 3, 0..65535 = black..sensor white
     * @param source frame whose colour tags, orientation and identity are copied
     * @param gainMaps lens shading maps for planes 0..2 over the whole image (may be empty)
     * @param noise NoiseProfile (scale, offset) of the merged data, or null
     */
    fun writeLinear(out: OutputStream, width: Int, height: Int, rgb: ShortArray, source: DngImage,
                    gainMaps: List<GainMap>, noise: Pair<Double, Double>?) {
        // compress strips first so their offsets are known
        val rowsPerStrip = 64
        val stripCount = (height + rowsPerStrip - 1) / rowsPerStrip
        val strips = ArrayList<ByteArray>(stripCount)
        val n = width * 3
        val raw = ByteArray(rowsPerStrip * n * 2)
        val deflater = Deflater(4)
        val chunk = ByteArray(1 shl 16)
        for (s in 0 until stripCount) {
            val rows = minOf(rowsPerStrip, height - s * rowsPerStrip)
            for (r in 0 until rows) {
                val start = (s * rowsPerStrip + r) * n
                val o = r * n * 2
                // floating point predictor (TIFF technical note 3): high bytes of the row first,
                // then low bytes, then byte-wise differencing across pixels
                for (k in 0 until n) {
                    val half = app.spektralab.engine.Half.fromFloat((rgb[start + k].toInt() and 0xffff) / 65535f).toInt()
                    raw[o + k] = (half shr 8).toByte(); raw[o + n + k] = half.toByte()
                }
                for (k in 2 * n - 1 downTo 3) raw[o + k] = (raw[o + k] - raw[o + k - 3]).toByte()
            }
            deflater.reset()
            deflater.setInput(raw, 0, rows * n * 2)
            deflater.finish()
            val bos = ByteArrayOutputStream(rows * width * 3)
            while (!deflater.finished()) bos.write(chunk, 0, deflater.deflate(chunk))
            strips += bos.toByteArray()
        }
        deflater.end()

        val tags = arrayListOf(
            longs(254, 0), longs(256, width), longs(257, height), shorts(258, 16, 16, 16), shorts(259, 8),
            shorts(262, 34892), shorts(274, source.orientation), shorts(277, 3), longs(278, rowsPerStrip),
            shorts(284, 1), shorts(317, 3), shorts(339, 3, 3, 3), ascii(305, "SpektraLab burst merge"),
            bytes(50706, 1, 4, 0, 0), bytes(50707, 1, 4, 0, 0),
            // floating point data is 0..1; stated explicitly because some readers assume 65535 otherwise
            longs(50717, 1, 1, 1), longs(50719, 0, 0), longs(50720, width, height),
        )
        // identity, date and colour calibration from the source frame
        for ((id, t) in source.tags) {
            tags += when {
                t.ascii != null -> Tag(id, 2, t.ascii.size, t.ascii)
                t.type == 5 -> rationals(id, t.values)
                t.type == 10 -> srationals(id, t.values)
                t.type == 3 -> shorts(id, *IntArray(t.values.size) { t.values[it].toInt() })
                else -> longs(id, *IntArray(t.values.size) { t.values[it].toInt() })
            }
        }
        if (tags.none { it.id == 50708 }) tags += ascii(50708, "SpektraLab")
        noise?.let { (s, o) -> tags += doubles(51041, doubleArrayOf(s, o, s, o, s, o)) }
        if (gainMaps.isNotEmpty()) gainMapOpcodes(gainMaps, width, height).let { tags += Tag(51009, 7, it.size, it) }
        // placeholders: filled in below once the layout is known
        tags += longs(273, *IntArray(stripCount)); tags += longs(279, *IntArray(stripCount) { strips[it].size })
        tags.sortBy { it.id }

        val ifdSize = 2 + tags.size * 12 + 4
        var dataPos = 8 + ifdSize
        val offsets = HashMap<Tag, Int>()
        for (t in tags) if (t.data.size > 4) { offsets[t] = dataPos; dataPos += t.data.size + (t.data.size and 1) }
        val stripOffsets = IntArray(stripCount)
        for (i in 0 until stripCount) { stripOffsets[i] = dataPos; dataPos += strips[i].size + (strips[i].size and 1) }
        val offsetTag = tags.first { it.id == 273 }
        le(stripCount * 4).apply { stripOffsets.forEach { putInt(it) } }.array().copyInto(offsetTag.data)

        out.write(le(8).apply { put('I'.code.toByte()); put('I'.code.toByte()); putShort(42); putInt(8) }.array())
        val ifd = le(ifdSize)
        ifd.putShort(tags.size.toShort())
        for (t in tags) {
            ifd.putShort(t.id.toShort()); ifd.putShort(t.type.toShort()); ifd.putInt(t.count)
            if (t.data.size > 4) ifd.putInt(offsets[t]!!) else ifd.put(t.data.copyOf(4))
        }
        ifd.putInt(0)
        out.write(ifd.array())
        for (t in tags) if (t.data.size > 4) { out.write(t.data); if (t.data.size and 1 == 1) out.write(0) }
        for (s in strips) { out.write(s); if (s.size and 1 == 1) out.write(0) }
        out.flush()
    }

    /** DNG OpcodeList2 with one GainMap per colour plane (big endian, as the spec requires). */
    private fun gainMapOpcodes(maps: List<GainMap>, width: Int, height: Int): ByteArray {
        val size = 4 + maps.sumOf { 16 + 76 + it.gains.size * 4 }
        val o = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        o.putInt(maps.size)
        for (m in maps) {
            o.putInt(9); o.putInt(0x01030000); o.putInt(0); o.putInt(76 + m.gains.size * 4)
            o.putInt(0); o.putInt(0); o.putInt(height); o.putInt(width)
            o.putInt(m.plane); o.putInt(1); o.putInt(1); o.putInt(1)
            o.putInt(m.pointsV); o.putInt(m.pointsH)
            o.putDouble(m.spacingV); o.putDouble(m.spacingH); o.putDouble(m.originV); o.putDouble(m.originH)
            o.putInt(1)
            for (g in m.gains) o.putFloat(g)
        }
        return o.array()
    }
}
