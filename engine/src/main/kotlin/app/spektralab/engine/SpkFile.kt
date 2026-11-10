package app.spektralab.engine

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** N-dimensional float array stored row-major (C order), as written by numpy. */
class NdArray(val shape: IntArray, val data: FloatArray) {
    val size: Int get() = data.size
    operator fun get(i: Int): Float = data[i]
    operator fun get(i: Int, j: Int): Float = data[i * shape[1] + j]
    operator fun get(i: Int, j: Int, k: Int): Float = data[(i * shape[1] + j) * shape[2] + k]
    fun toDoubles(): DoubleArray = DoubleArray(data.size) { data[it].toDouble() }
}

/**
 * Reader for the SPK1 container produced by `tools/bake/spk.py`
 * (spektrafilm data baked for the native engine).
 */
class SpkFile private constructor(
    private val arrays: Map<String, NdArray>,
    private val strings: Map<String, String>,
) {
    fun array(name: String): NdArray = arrays[name] ?: error("SPK entry '$name' not found")
    fun arrayOrNull(name: String): NdArray? = arrays[name]
    fun string(name: String): String = strings[name] ?: error("SPK string '$name' not found")
    fun stringOrNull(name: String): String? = strings[name]
    fun arrayNames(prefix: String = ""): List<String> = arrays.keys.filter { it.startsWith(prefix) }

    companion object {
        fun read(input: InputStream): SpkFile {
            val bytes = input.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "SPK1") { "not an SPK1 file" }
            val count = buf.int
            val arrays = HashMap<String, NdArray>()
            val strings = HashMap<String, String>()
            repeat(count) {
                val nameLen = buf.short.toInt() and 0xffff
                val nameBytes = ByteArray(nameLen).also { buf.get(it) }
                val name = String(nameBytes, Charsets.UTF_8)
                when (val kind = buf.get().toInt()) {
                    1 -> {
                        val n = buf.int
                        val s = ByteArray(n).also { buf.get(it) }
                        strings[name] = String(s, Charsets.UTF_8)
                    }
                    0, 2 -> {
                        val ndim = buf.get().toInt()
                        val shape = IntArray(ndim) { buf.int }
                        val total = shape.fold(1) { a, b -> a * b }
                        val data = FloatArray(total)
                        if (kind == 0) {
                            for (i in 0 until total) data[i] = buf.float
                        } else {
                            for (i in 0 until total) data[i] = Half.toFloat(buf.short)
                        }
                        arrays[name] = NdArray(shape, data)
                    }
                    else -> error("unknown SPK entry kind $kind")
                }
            }
            return SpkFile(arrays, strings)
        }
    }
}

/** IEEE 754 half precision conversion (no dependency on android.util.Half). */
object Half {
    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = bits ushr 15
        val exp = (bits ushr 10) and 0x1f
        val mant = bits and 0x3ff
        val f: Int = when {
            exp == 0 && mant == 0 -> sign shl 31
            exp == 0 -> {
                var m = mant
                var e = -14
                while (m and 0x400 == 0) { m = m shl 1; e-- }
                m = m and 0x3ff
                (sign shl 31) or ((e + 127) shl 23) or (m shl 13)
            }
            exp == 31 -> (sign shl 31) or (0xff shl 23) or (mant shl 13)
            else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(f)
    }

    fun fromFloat(value: Float): Short {
        val f = java.lang.Float.floatToIntBits(value)
        val sign = (f ushr 16) and 0x8000
        var exp = ((f ushr 23) and 0xff) - 127 + 15
        var mant = f and 0x7fffff
        if (((f ushr 23) and 0xff) == 0xff) {
            return (sign or 0x7c00 or (if (mant != 0) 0x200 else 0)).toShort()
        }
        if (exp >= 31) return (sign or 0x7c00).toShort()
        if (exp <= 0) {
            if (exp < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - exp
            var half = mant shr shift
            if ((mant shr (shift - 1)) and 1 != 0) half += 1
            return (sign or half).toShort()
        }
        var half = sign or (exp shl 10) or (mant shr 13)
        if (mant and 0x1000 != 0) half += 1 // round to nearest
        return half.toShort()
    }
}
