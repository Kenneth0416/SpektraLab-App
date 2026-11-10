package app.spektralab.engine

/*
 * Kotlin port of the subset of spektrafilm `runtime/params_schema.py` that the
 * mobile engine uses. Defaults match spektrafilm.
 */

data class DirCouplersParams(
    var active: Boolean = true,
    var amount: Double = 1.0,
    var inhibitionSamelayer: Double = 1.0,
    var inhibitionInterlayer: Double = 1.0,
    var gammaSamelayerRgb: DoubleArray = doubleArrayOf(0.341, 0.324, 0.273),
    var gammaInterlayerRToGb: DoubleArray = doubleArrayOf(0.355, 0.305),
    var gammaInterlayerGToRb: DoubleArray = doubleArrayOf(0.154, 0.358),
    var gammaInterlayerBToRg: DoubleArray = doubleArrayOf(0.171, 0.225),
    var diffusionSizeUm: Double = 20.0,
    var diffusionTailUm: Double = 200.0,
    var diffusionTailWeight: Double = 0.06,
)

data class GrainParams(
    var active: Boolean = true,
    var particleAreaUm2: Double = 0.2,
    var particleScale: DoubleArray = doubleArrayOf(1.6, 1.6, 3.2),
    var particleScaleLayers: DoubleArray = doubleArrayOf(2.0, 1.0, 0.5),
    var densityMin: DoubleArray = doubleArrayOf(0.03, 0.03, 0.03),
    var uniformity: DoubleArray = doubleArrayOf(0.97, 0.99, 0.97),
    var blur: Double = 0.65,
    /** App extension: multiplier on the stochastic deviation (1 = spektrafilm). */
    var amount: Double = 1.0,
)

data class HalationParams(
    var active: Boolean = true,
    var scatterAmount: Double = 1.0,
    var scatterSpatialScale: Double = 1.0,
    var halationAmount: Double = 1.0,
    var halationSpatialScale: Double = 1.0,
    var scatterCoreUm: DoubleArray = doubleArrayOf(2.2, 2.0, 1.6),
    var scatterTailUm: DoubleArray = doubleArrayOf(9.3, 9.7, 9.1),
    var scatterTailWeight: DoubleArray = doubleArrayOf(0.78, 0.65, 0.67),
    var halationStrength: DoubleArray = doubleArrayOf(0.05, 0.015, 0.0),
    var halationFirstSigmaUm: DoubleArray = doubleArrayOf(65.0, 65.0, 65.0),
    var halationNBounces: Int = 3,
    var halationBounceDecay: Double = 0.5,
    var halationRenormalize: Boolean = true,
)

data class DiffusionFilterParams(
    var active: Boolean = false,
    var family: String = "black_pro_mist",
    var strength: Double = 0.5,
    var spatialScale: Double = 1.0,
    var haloWarmth: Double = 0.0,
)

data class EnlargerParams(
    var printExposure: Double = 1.0,
    var printExposureCompensation: Boolean = true,
    var normalizePrintExposure: Boolean = true,
    var yFilterShift: Double = 0.0,
    var mFilterShift: Double = 0.0,
    var yFilterNeutral: Double = 55.0,
    var mFilterNeutral: Double = 65.0,
    var cFilterNeutral: Double = 0.0,
)

data class RuntimeParams(
    val film: FilmProfile,
    val paper: PaperProfile?,
    /** Skip the print stage and scan the film directly (always true for reversal film). */
    var scanFilm: Boolean = false,
    var exposureCompensationEv: Double = 0.0,
    var densityCurveGamma: Double = 1.0,
    /** Film format long edge in mm (pixel size = format / long edge pixels). */
    var filmFormatMm: Double = 36.0,
    val couplers: DirCouplersParams = DirCouplersParams(),
    val grain: GrainParams = GrainParams(),
    val halation: HalationParams = HalationParams(),
    val diffusion: DiffusionFilterParams = DiffusionFilterParams(),
    val enlarger: EnlargerParams = EnlargerParams(),
    /** Print glare (percent of the viewing illuminant), spektrafilm default 0.03. */
    var glarePercent: Double = 0.03,
) {
    companion object {
        /**
         * Build params like `init_params` + `digest_params`: database neutral
         * print filtration plus film/print specifics (coupler gammas, halation
         * presets).
         */
        fun digest(film: FilmProfile, paper: PaperProfile?, global: GlobalData, scanFilm: Boolean): RuntimeParams {
            val p = RuntimeParams(film, paper, scanFilm = scanFilm || film.isPositive || paper == null)
            if (paper != null) {
                global.neutralFilters(paper.stock, film.stock)?.let {
                    p.enlarger.cFilterNeutral = it[0]
                    p.enlarger.mFilterNeutral = it[1]
                    p.enlarger.yFilterNeutral = it[2]
                }
            }
            applyFilmSpecifics(p)
            return p
        }

        private fun applyFilmSpecifics(p: RuntimeParams) {
            val c = p.couplers
            if (p.film.isPositive) {
                c.gammaSamelayerRgb = doubleArrayOf(0.12, 0.08, 0.06)
                c.gammaInterlayerRToGb = doubleArrayOf(0.12, 0.06)
                c.gammaInterlayerGToRb = doubleArrayOf(0.08, 0.06)
                c.gammaInterlayerBToRg = doubleArrayOf(0.06, 0.06)
            }
            if (p.film.isNegative) {
                c.gammaSamelayerRgb = doubleArrayOf(0.336, 0.319, 0.273)
                c.gammaInterlayerRToGb = doubleArrayOf(0.353, 0.302)
                c.gammaInterlayerGToRb = doubleArrayOf(0.154, 0.353)
                c.gammaInterlayerBToRg = doubleArrayOf(0.168, 0.226)
            }
            val sigmaH = if (p.film.use == "cine") 50.0 else 65.0
            val strength = when (p.film.antihalation) {
                "strong" -> doubleArrayOf(0.015, 0.005, 0.0)
                "weak" -> doubleArrayOf(0.08, 0.02, 0.0)
                "no" -> doubleArrayOf(0.30, 0.10, 0.015)
                else -> null
            }
            if (strength != null) {
                p.halation.halationFirstSigmaUm = doubleArrayOf(sigmaH, sigmaH, sigmaH)
                p.halation.halationStrength = strength
            }
            when (p.film.stock) {
                "fujifilm_velvia_100" -> {
                    c.gammaSamelayerRgb = doubleArrayOf(0.108, 0.072, 0.054)
                    c.gammaInterlayerRToGb = doubleArrayOf(0.108, 0.054)
                    c.gammaInterlayerGToRb = doubleArrayOf(0.072, 0.054)
                    c.gammaInterlayerBToRg = doubleArrayOf(0.054, 0.054)
                }
                "fujifilm_provia_100f" -> {
                    c.gammaSamelayerRgb = doubleArrayOf(0.156, 0.104, 0.078)
                    c.gammaInterlayerRToGb = doubleArrayOf(0.156, 0.078)
                    c.gammaInterlayerGToRb = doubleArrayOf(0.104, 0.078)
                    c.gammaInterlayerBToRg = doubleArrayOf(0.078, 0.078)
                }
            }
        }
    }
}
