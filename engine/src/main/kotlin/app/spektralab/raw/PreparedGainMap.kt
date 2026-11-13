package app.spektralab.raw

/**
 * The same bilinear interpolation as [GainMap.gain], with its coordinate transforms
 * prepared once per development. Storage is O(cropWidth + cropHeight), not a full
 * sensor-sized gain plane. Coordinates passed to [gain] are relative to the crop.
 */
internal class PreparedGainMap(private val map: GainMap, private val img: RawImage, precompute: Boolean = true) {
    companion object {
        // Six primitive arrays plus two Axis objects; reserve headers/alignment too.
        // This is conservative on both Android ART and the supported desktop JVMs.
        internal fun axisBytes(img: RawImage): Long = 12L * (img.cropWidth.toLong() + img.cropHeight) + 256L
    }

    private class Axis(size: Int, crop: Int, sensor: Int, origin: Double, spacing: Double, points: Int, stride: Int) {
        val low = IntArray(size)
        val high = IntArray(size)
        val weight = FloatArray(size)

        init {
            for (i in 0 until size) {
                // Keep the original operation order and Double -> Float rounding.
                val p = (((crop + i).toDouble() / sensor - origin) / spacing).coerceIn(0.0, (points - 1).toDouble())
                val p0 = p.toInt().coerceAtMost(points - 2).coerceAtLeast(0)
                low[i] = p0 * stride
                high[i] = minOf(p0 + 1, points - 1) * stride
                weight[i] = (p - p0).toFloat()
            }
        }
    }

    private val x = if (precompute) Axis(img.cropWidth, img.cropX, img.rawWidth, map.originH, map.spacingH, map.pointsH, 1) else null
    private val y = if (precompute) Axis(img.cropHeight, img.cropY, img.rawHeight, map.originV, map.spacingV, map.pointsV, map.pointsH) else null
    private val gains = map.gains

    fun gain(cx: Int, cy: Int): Float {
        val x = x; val y = y
        if (x == null || y == null) return map.gain(img.cropX + cx, img.cropY + cy, img.rawWidth, img.rawHeight)
        val x0 = x.low[cx]; val x1 = x.high[cx]
        val y0 = y.low[cy]; val y1 = y.high[cy]
        val fx = x.weight[cx]; val fy = y.weight[cy]
        val a = gains[y0 + x0] * (1 - fx) + gains[y0 + x1] * fx
        val b = gains[y1 + x0] * (1 - fx) + gains[y1 + x1] * fx
        return a * (1 - fy) + b * fy
    }
}
