package app.spektralab.engine

import java.io.InputStream

/** Number of wavelength samples (380..780 nm, 5 nm) used by spektrafilm. */
const val N_WL = 81

/** Film stock (negative or reversal) data, baked from a spektrafilm profile. */
class FilmProfile(spk: SpkFile) {
    val stock = spk.string("info/stock")
    val name = spk.string("info/name")
    val type = spk.string("info/type")
    val use = spk.string("info/use")
    val antihalation = spk.string("info/antihalation")
    val referenceIlluminant = spk.string("info/reference_illuminant")
    val targetPrint = spk.string("info/target_print")
    val isPositive get() = type == "positive"
    val isNegative get() = type == "negative"

    val logExposure: DoubleArray = spk.array("log_exposure").toDoubles()
    /** Raw density curves [256][3] (not normalised). */
    val densityCurves: Array<DoubleArray> = rows(spk.array("density_curves"))
    /** Sublayer density curves [256][3 sublayers][3 channels]. */
    val densityCurvesLayers: NdArray = spk.array("density_curves_layers")
    /** Dye absorption spectra [81][3]. NaN marks wavelengths with no data. */
    val channelDensity: Array<DoubleArray> = rows(spk.array("channel_density"))
    val baseDensity: DoubleArray = spk.array("base_density").toDoubles()
    /** Hanatos 2025 spectral-upsampling LUT in triangular-chromaticity coords, [L][L][3]. */
    val tcLut: NdArray = spk.array("tc_lut")
    /** Linear sRGB -> CIE XYZ, CAT16-adapted to the film reference illuminant (row-major). */
    val mSrgbToXyz: DoubleArray = spk.array("m_srgb_to_xyz").toDoubles()
    /** Direct-scan LUT: film CMY density -> linear sRGB. */
    val scanLut: Lut3 = Lut3.fromSpk(spk.array("scan_lut"), spk.array("scan_lut_range"))

    val normalizedDensityCurves: Array<DoubleArray> by lazy {
        val mins = DoubleArray(3) { c -> densityCurves.minOf { it[c] } }
        Array(densityCurves.size) { i -> DoubleArray(3) { c -> densityCurves[i][c] - mins[c] } }
    }
    val densityMaxNormalized: DoubleArray by lazy {
        DoubleArray(3) { c -> normalizedDensityCurves.maxOf { it[c] } }
    }

    companion object {
        fun read(input: InputStream) = FilmProfile(SpkFile.read(input))
    }
}

/** Print material (paper or print film), baked from a spektrafilm profile. */
class PaperProfile(spk: SpkFile) {
    val stock = spk.string("info/stock")
    val name = spk.string("info/name")
    val type = spk.string("info/type")
    val logExposure: DoubleArray = spk.array("log_exposure").toDoubles()
    val logSensitivity: Array<DoubleArray> = rows(spk.array("log_sensitivity"))
    /** Print density curves evaluated from the fitted model (as `develop_print_morph`). */
    val densityCurves: Array<DoubleArray> = rows(spk.array("density_curves"))
    val channelDensity: Array<DoubleArray> = rows(spk.array("channel_density"))
    val baseDensity: DoubleArray = spk.array("base_density").toDoubles()
    /** Scanner LUT: print CMY density -> linear sRGB (viewing illuminant, CAT, gamut compression). */
    val scanLut: Lut3 = Lut3.fromSpk(spk.array("scan_lut"), spk.array("scan_lut_range"))

    /** 10^log_sensitivity with NaN -> 0 (np.nan_to_num). */
    val sensitivity: Array<DoubleArray> by lazy {
        Array(N_WL) { l -> DoubleArray(3) { c -> nanToNum(Math.pow(10.0, logSensitivity[l][c])) } }
    }

    companion object {
        fun read(input: InputStream) = PaperProfile(SpkFile.read(input))
    }
}

/** Data shared by all stocks: enlarger lamp, dichroic filters, neutral filtration database. */
class GlobalData(spk: SpkFile) {
    val wavelengths: DoubleArray = spk.array("wavelengths").toDoubles()
    /** Tungsten halogen lamp with KG3 heat filter, normalised as `standard_illuminant`. */
    val enlargerIlluminant: DoubleArray = spk.array("illuminant/TH-KG3").toDoubles()
    /** Custom dichroic C/M/Y filter transmittances [81][3]. */
    val dichroicFilters: Array<DoubleArray> = rows(spk.array("dichroic_filters"))
    private val neutral: Map<String, DoubleArray> = spk.arrayNames("neutral_filters/")
        .associate { it.removePrefix("neutral_filters/") to spk.array(it).toDoubles() }

    /** Neutral C/M/Y CC filtration for [paper] + [film] from spektrafilm's database. */
    fun neutralFilters(paper: String, film: String): DoubleArray? = neutral["$paper/$film"]

    companion object {
        fun read(input: InputStream) = GlobalData(SpkFile.read(input))
    }
}

/** Uniform 3D LUT on an axis-aligned box, values [n][n][n][3] (r index slowest). */
class Lut3(val n: Int, val lo: DoubleArray, val hi: DoubleArray, val values: FloatArray) {
    /** Trilinear lookup, clamped to the box. */
    fun sample(x0: Double, x1: Double, x2: Double, out: DoubleArray) {
        val f = DoubleArray(3)
        val i = IntArray(3)
        val xs = doubleArrayOf(x0, x1, x2)
        for (c in 0..2) {
            var t = (xs[c] - lo[c]) / (hi[c] - lo[c]) * (n - 1)
            if (t < 0.0) t = 0.0
            if (t > (n - 1).toDouble()) t = (n - 1).toDouble()
            var b = t.toInt()
            if (b >= n - 1) b = n - 2
            i[c] = b
            f[c] = t - b
        }
        out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
        for (dr in 0..1) for (dg in 0..1) for (db in 0..1) {
            val w = (if (dr == 0) 1 - f[0] else f[0]) * (if (dg == 0) 1 - f[1] else f[1]) *
                (if (db == 0) 1 - f[2] else f[2])
            if (w == 0.0) continue
            val idx = (((i[0] + dr) * n + (i[1] + dg)) * n + (i[2] + db)) * 3
            out[0] += w * values[idx]
            out[1] += w * values[idx + 1]
            out[2] += w * values[idx + 2]
        }
    }

    companion object {
        fun fromSpk(lut: NdArray, range: NdArray): Lut3 {
            val n = lut.shape[0]
            return Lut3(n, DoubleArray(3) { range[0, it].toDouble() },
                DoubleArray(3) { range[1, it].toDouble() }, lut.data)
        }
    }
}

internal fun rows(a: NdArray): Array<DoubleArray> {
    val cols = a.shape[1]
    return Array(a.shape[0]) { i -> DoubleArray(cols) { j -> a[i, j].toDouble() } }
}

internal fun nanToNum(v: Double): Double = when {
    v.isNaN() -> 0.0
    v == Double.POSITIVE_INFINITY -> Double.MAX_VALUE
    v == Double.NEGATIVE_INFINITY -> -Double.MAX_VALUE
    else -> v
}
