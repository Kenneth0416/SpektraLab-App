package app.spektralab.engine

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** Checks the Kotlin port against vectors produced by the spektrafilm Python reference. */
class ReferenceTest {
    private val assets = File(System.getProperty("spektra.assets"))
    private val global = GlobalData.read(File(assets, "global.spk").inputStream())

    private fun film(s: String) = FilmProfile.read(File(assets, "film/$s.spk").inputStream())
    private fun paper(s: String) = PaperProfile.read(File(assets, "paper/$s.spk").inputStream())

    private fun cases(): List<SpkFile> =
        File(javaClass.getResource("/reference")!!.toURI()).listFiles()!!
            .filter { it.name.endsWith(".spk") }.sortedBy { it.name }
            .map { SpkFile.read(it.inputStream()) }

    private fun field(json: String, key: String): String? =
        Regex("\"$key\": (\"[^\"]*\"|[-0-9.eE]+|true|false)").find(json)?.groupValues?.get(1)?.trim('"')

    private fun buildParams(json: String): RuntimeParams {
        val f = film(field(json, "film")!!)
        val p = paper(field(json, "paper")!!)
        val scan = field(json, "scan_film") == "true"
        val rp = RuntimeParams.digest(f, p, global, scan)
        field(json, "ev")?.let { rp.exposureCompensationEv = it.toDouble() }
        field(json, "print_exposure")?.let { rp.enlarger.printExposure = it.toDouble() }
        field(json, "y_shift")?.let { rp.enlarger.yFilterShift = it.toDouble() }
        field(json, "m_shift")?.let { rp.enlarger.mFilterShift = it.toDouble() }
        field(json, "gamma")?.let { rp.densityCurveGamma = it.toDouble() }
        field(json, "couplers")?.let { rp.couplers.active = it == "true" }
        return rp
    }

    private class Err { var max = 0.0; var sum = 0.0; var n = 0
        fun add(e: Double) { max = max(max, e); sum += e; n++ }
        override fun toString() = "max=%.5f mean=%.5f".format(max, sum / max(n, 1))
    }

    private fun srgbEncode(x: Double): Double {
        val v = x.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    }

    @Test
    fun matchesSpektrafilm() {
        val failures = mutableListOf<String>()
        for (case in cases()) {
            val json = case.string("case")
            val name = field(json, "name")
            val params = buildParams(json)
            val pipe = PixelPipeline(params, global)
            val lut = pipe.buildOutputLut(40)
            val input = case.array("rgb_in")
            val refLogE = case.array("log_e_film")
            val refCmy = case.array("cmy_film")
            val refLogP = case.arrayOrNull("log_e_print")
            val refCmyP = case.arrayOrNull("cmy_print")
            val refOut = case.array("rgb_out")
            val eLogE = Err(); val eCmy = Err(); val eLogP = Err(); val eCmyP = Err(); val eOut = Err(); val eLut = Err()
            val taps = PixelPipeline.Taps()
            val lutOut = DoubleArray(3)
            for (i in 0 until input.shape[0]) {
                pipe.process(input[i, 0].toDouble(), input[i, 1].toDouble(), input[i, 2].toDouble(), taps)
                lut.sample(taps.cmyFilm[0], taps.cmyFilm[1], taps.cmyFilm[2], lutOut)
                for (c in 0..2) {
                    // only score exposures inside the curve range (both clamp outside)
                    eLogE.add(abs(taps.logEFilm[c] - refLogE[i, c]).coerceAtMost(if (refLogE[i, c] < -3) 0.0 else 9.0))
                    eCmy.add(abs(taps.cmyFilm[c] - refCmy[i, c]))
                    if (refLogP != null) eLogP.add(abs(taps.logEPrint[c] - refLogP[i, c]))
                    if (refCmyP != null) eCmyP.add(abs(taps.cmyPrint[c] - refCmyP[i, c]))
                    val ref = srgbEncode(refOut[i, c].toDouble())
                    eOut.add(abs(srgbEncode(taps.rgbOut[c]) - ref))
                    eLut.add(abs(srgbEncode(lutOut[c]) - ref))
                }
            }
            println("$name: logE $eLogE | cmy $eCmy | logEprint $eLogP | cmyPrint $eCmyP | out(sRGB) $eOut | lut(sRGB) $eLut")
            if (eCmy.max > 0.01 || eOut.max > 0.02 || eLut.max > 0.03) failures += name ?: "?"
        }
        assertTrue(failures.isEmpty(), "cases off reference: $failures")
    }
}
