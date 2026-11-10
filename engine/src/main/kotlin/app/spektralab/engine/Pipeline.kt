package app.spektralab.engine

/**
 * Per-pixel spektrafilm pipeline (film -> print -> scan) for one set of
 * runtime params. Spatial and stochastic effects (halation, diffusion,
 * coupler diffusion, grain) are prepared separately for the GPU renderer.
 */
class PixelPipeline(val params: RuntimeParams, val global: GlobalData) {
    val filming = FilmingModel(params)
    val printing: PrintingModel? = if (params.scanFilm) null else PrintingModel(params, global, filming)

    /** Domain of the film-density -> output LUT (same box as the baked film scan LUT). */
    val filmDensityLo: DoubleArray get() = params.film.scanLut.lo
    val filmDensityHi: DoubleArray get() = params.film.scanLut.hi

    class Taps {
        val logEFilm = DoubleArray(3)
        val cmyFilm = DoubleArray(3)
        val logEPrint = DoubleArray(3)
        val cmyPrint = DoubleArray(3)
        val rgbOut = DoubleArray(3)
    }

    /** Exact evaluation (spectral print stage computed per call), linear sRGB in/out. */
    fun process(r: Double, g: Double, b: Double, taps: Taps = Taps()): Taps {
        filming.exposeLog(r, g, b, taps.logEFilm)
        filming.develop(taps.logEFilm, taps.cmyFilm)
        filmDensityToRgb(taps.cmyFilm, taps)
        return taps
    }

    fun filmDensityToRgb(cmyFilm: DoubleArray, taps: Taps) {
        val pr = printing
        if (pr == null) {
            params.film.scanLut.sample(cmyFilm[0], cmyFilm[1], cmyFilm[2], taps.rgbOut)
        } else {
            pr.expose(cmyFilm, taps.logEPrint)
            pr.develop(taps.logEPrint, taps.cmyPrint)
            params.paper!!.scanLut.sample(taps.cmyPrint[0], taps.cmyPrint[1], taps.cmyPrint[2], taps.rgbOut)
        }
    }

    /**
     * Film CMY density -> linear sRGB 3D LUT ([n][n][n][3], r slowest) over
     * [filmDensityLo, filmDensityHi]. This folds enlarger filtration, print
     * exposure, paper curves and the scanner into one table.
     */
    fun buildOutputLut(n: Int = 40): Lut3 {
        val lo = filmDensityLo
        val hi = filmDensityHi
        val out = FloatArray(n * n * n * 3)
        val pr = printing
        val tmp = DoubleArray(3)
        if (pr == null) {
            for (i in 0 until n) for (j in 0 until n) for (k in 0 until n) {
                val idx = ((i * n + j) * n + k) * 3
                params.film.scanLut.sample(
                    lo[0] + (hi[0] - lo[0]) * i / (n - 1),
                    lo[1] + (hi[1] - lo[1]) * j / (n - 1),
                    lo[2] + (hi[2] - lo[2]) * k / (n - 1), tmp)
                out[idx] = tmp[0].toFloat(); out[idx + 1] = tmp[1].toFloat(); out[idx + 2] = tmp[2].toFloat()
            }
        } else {
            val printDensity = pr.buildFilmToPrintDensityLut(n, lo, hi)
            val scan = params.paper!!.scanLut
            for (idx in 0 until n * n * n) {
                val o = idx * 3
                scan.sample(printDensity[o].toDouble(), printDensity[o + 1].toDouble(), printDensity[o + 2].toDouble(), tmp)
                out[o] = tmp[0].toFloat(); out[o + 1] = tmp[1].toFloat(); out[o + 2] = tmp[2].toFloat()
            }
        }
        return Lut3(n, lo.copyOf(), hi.copyOf(), out)
    }
}
