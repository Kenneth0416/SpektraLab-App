package app.spektralab.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RawDevelopmentCompatibilityTest {
    private fun hash(buf: ByteBuffer): String = MessageDigest.getInstance("SHA-256").let { d ->
        d.update(buf.duplicate().apply { position(0) })
        d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cropped(img: RawImage, orientation: Int = 1, maps: List<GainMap> = img.gainMaps) = RawImage(
        img.rawWidth, img.rawHeight, img.data, img.cfa, img.black, img.white, 3, 5, 160, 120,
        img.wb, img.camToSrgb, orientation, img.baselineExposure, maps, img.make, img.model,
        img.noiseScale, img.noiseOffset, tags = img.tags,
    )

    private fun budgetImage(w: Int, h: Int, maps: List<GainMap>, linear: Boolean = true): RawImage = RawImage(
        w, h, ShortArray(w * h * if (linear) 3 else 1),
        CfaPattern(2, 2, intArrayOf(0, 1, 1, 2)), FloatArray(4), 65535f,
        0, 0, w, h, floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        gainMaps = maps, linear = linear,
    )

    private fun budgetMap(plane: Int = 0, value: Float = 1f) = GainMap(
        0, 0, Int.MAX_VALUE, Int.MAX_VALUE, 1, 1, 1, 1, 1.0, 1.0, 0.0, 0.0, floatArrayOf(value), plane,
    )

    @Test fun gainTablesStayInBudgetEvenWithManyLinearPlaneMaps() {
        val maps = List(300) { budgetMap(it % 3, 1f + it / 1000f) }
        val img = budgetImage(1024, 768, maps)
        val perMap = 12L * (1024 + 768) + 256L
        val expected = (RawDeveloper.GAIN_TABLE_BUDGET / perMap) * perMap
        assertEquals(expected, RawDeveloper.preparedGainBytes(img))
        assertTrue(expected <= RawDeveloper.GAIN_TABLE_BUDGET)
        // Exercise both prepared and fallback maps in the actual development prep.
        val prep = RawDeveloper.Prep(img, RawDeveloper.Options())
        for (c in 0..2) {
            val selected = maps.filter { it.plane == c }
            val reference = selected.fold(0f) { sum, map -> sum + map.gain(700, 500, 1024, 768) } / selected.size
            assertEquals(reference.toRawBits(), prep.gain(c, 700, 500).toRawBits())
        }
    }

    @Test fun oversizedAxesFallBackWithoutChargingOrAllocatingTables() {
        val img = budgetImage(180_000, 1, listOf(budgetMap(value = 1.25f)))
        assertEquals(0L, RawDeveloper.preparedGainBytes(img))
        assertEquals(1.25f, RawDeveloper.Prep(img, RawDeveloper.Options()).gain(2, 179_999, 0))
    }

    @Test fun sharedAndUnusedMapsAreNotChargedMultipleTimes() {
        val shared = budgetMap()
        val img = budgetImage(1024, 768, listOf(shared), linear = false)
        val oneMap = 12L * (1024 + 768) + 256L
        assertEquals(oneMap, RawDeveloper.preparedGainBytes(img))
        // Bayer selection uses the first covering map for each CFA position.
        val unused = budgetImage(1024, 768, listOf(shared, budgetMap(value = 2f)), linear = false)
        assertEquals(oneMap, RawDeveloper.preparedGainBytes(unused))
    }

    @Test fun budgetedEdgesReserveFullCropTablesEvenForHeavilyBinnedOutput() {
        val img = budgetImage(1024, 768, (0..2).map { budgetMap(it) })
        val tables = 3L * (12L * (1024 + 768) + 256L)
        val pixels = 64L * 48 * 6
        assertEquals(tables + pixels, RawDeveloper.workingBytes(img, 64, true, false))
        val budget = tables + pixels
        val edge = RawDeveloper.longEdgeWithin(img, 512, true, budget, false)
        assertTrue(edge < 512)
        assertTrue(RawDeveloper.workingBytes(img, edge, true, false) <= budget)
        val out = RawDeveloper.develop(img, edge, fast = true)
        assertTrue(out.width.toLong() * out.height * 6 + tables <= budget)
        assertFailsWith<IllegalArgumentException> {
            RawDeveloper.longEdgeWithin(img, 512, true, tables - 1, false)
        }
        // Bayer's dimension-only selector must reserve the same image-aware cost.
        val bayer = budgetImage(1024, 768, listOf(budgetMap()), linear = false)
        val bayerBudget = RawDeveloper.preparedGainBytes(bayer) + pixels
        val bayerEdge = RawDeveloper.longEdgeWithin(bayer, 512, true, bayerBudget, false)
        assertTrue(RawDeveloper.workingBytes(bayer, bayerEdge, true, false) <= bayerBudget)
    }

    @Test fun preparedGainMatchesOriginalInterpolationIncludingClampsAndSinglePointAxes() {
        val img = cropped(assertNotNull(DngReader.read(RawFixtures.bayerDng(168, 128, 31))))
        for ((pv, ph) in listOf(13 to 17, 1 to 1, 1 to 7, 5 to 1)) {
            for ((ov, oh) in listOf(0.0 to 0.0, 0.2 to -0.1, -0.4 to 0.6)) {
                val map = GainMap(0, 0, 128, 168, 1, 1, pv, ph, 0.07, 0.04, ov, oh,
                    FloatArray(pv * ph) { 0.7f + ((it * 17) % 71) / 31f })
                for (precompute in listOf(true, false)) {
                    val prepared = PreparedGainMap(map, img, precompute)
                    for (y in 0 until img.cropHeight) for (x in 0 until img.cropWidth) {
                        assertEquals(map.gain(x + img.cropX, y + img.cropY, img.rawWidth, img.rawHeight).toRawBits(),
                            prepared.gain(x, y).toRawBits(), "grid=${pv}x$ph origin=$ov,$oh pixel=$x,$y prepared=$precompute")
                    }
                }
            }
        }
    }

    @Test fun highlightReconstructionMatchesPreOptimizationGoldenValues() {
        // Recorded from c99483f, before changing the algorithm, using RawFixtures.sourceFrame.
        val expected = mapOf(true to "977d4b1522a041de2e63f38dff7412a2cf62d9433508e0ae3f94fa56ae949961",
            false to "b4c7c6c5af13a0cd5328b5b2ab8d38d80b3441b81918ef671d52ca1a3d1dc57f")
        val img = RawFixtures.sourceFrame()
        for ((enabled, golden) in expected) {
            val prep = RawDeveloper.Prep(img, RawDeveloper.Options(enabled, false))
            val random = Random(781)
            val pixels = ByteBuffer.allocate(8192 * 12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(8192) {
                val rgb = FloatArray(3) { c -> random.nextFloat() * img.wb[c] * 1.2f }
                prep.reconstruct(rgb)
                rgb.forEach { pixels.putFloat(it) }
            }
            assertEquals(golden, hash(pixels), "reconstruction=$enabled")
        }
    }

    @Test fun fullAndBinnedDevelopmentPreserveEveryOutputBitAcrossEightOrientations() {
        // c99483f output: odd crop origin, four lens maps, clipped samples, denoise on.
        val golden = listOf(
            "18add20f5b605b171d4c723226217bc775b7c658d3358e982aca6ff19f7b847e", "2c5aea313f0c420c67176358e139c09e37d74a20d30494b701f5446461bee065",
            "0f816df0d1bb9e94ea89572734cbe85c84ea54bc5bd843925fafc0bac28cbe4d", "8682c44ff9776403d0e1dd261b9eaa20aa20cdbb0d398c003fe69af9920b2a5f",
            "55079234d2b4d52f5816be83167e05c89c50ddca09a4cc45b9e1a9f90ea82b4b", "0b3672d08dbbda68633f29cd0a37cadf9fdaa7d2f31e44d9f3fe8660789bf401",
            "b13a6893e1449361b738f39d79b356477ca01334f909fce01770a9a640036889", "68a9ff0fba5415ff07d2fd597e16982ca5294ae535973aadcfcbb20f6f19e025",
            "ee95e6529ea7afad559ebbbaac7408060c77533e8c30fd70a130727ea8f58d76", "3c6655799b44997b2a8902e2bded1a95dd2ea9e55c19d28830ee4a565ece8da8",
            "71197e86c6e1b2991d5f00555f5a1b3928b7b9cc3d5215e2c62322be9d04313b", "3c46eb51d745040e653f55cc9b2aa79a830001cd5041480153a9ed506f749f1f",
            "dc4b4be62a058e950d5cb2840d8be4c755b93bb4652ccd174c7c7ffdca070e2f", "8f1bea98604e8a61a5b6c11fb80bba639a78d4d5733e444817b9564743a24958",
            "051cb637986a5f333fe51612c680df280e76b9e68030043ea45dedb64d38f2b1", "ce69818c55bdfd098fe0f1b6a644f21d5e2dd898ab44b47df1df42b076223500",
        )
        val img = assertNotNull(DngReader.read(RawFixtures.bayerDng(168, 128, 31)))
        for (i in img.data.indices step 13) img.data[i] = 1023
        var i = 0
        for (orientation in 1..8) for (edge in listOf(48, 4096)) {
            val result = RawDeveloper.develop(cropped(img, orientation), edge)
            assertEquals(golden[i++], hash(result.halfRgba), "orientation=$orientation edge=$edge")
        }
    }
}
