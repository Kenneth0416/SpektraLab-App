package app.spektralab.raw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expected values were read from LibRaw 0.22.2 `src/tables/colordata.cpp` (`adobe_coeff`), the
 * integers there are the matrix x10000.
 */
class CameraMatricesTest {
    private fun ints(e: CameraMatrixEntry): List<Int> =
        e.xyzToCamera!!.flatMap { row -> row.map { Math.round(it * 10000).toInt() } }

    @Test
    fun tableIsLoaded() {
        assertTrue(CameraMatrices.size > 700, "table size ${CameraMatrices.size}")
        assertTrue(CameraMatrices.SOURCE_VERSION.startsWith("LibRaw 0."))
        // a 3-colour row has 3 rows of 3, nothing is silently dropped
        for (e in CameraMatrices.all) {
            val m = e.xyzToCamera
            if (m == null) {
                assertEquals(0, e.colors)
            } else {
                assertEquals(e.colors, m.size)
                assertTrue(e.colors == 3 || e.colors == 4)
                m.forEach { assertEquals(3, it.size) }
            }
        }
    }

    @Test
    fun canon5dMarkII() {
        val e = assertNotNull(CameraMatrices.find("Canon", "Canon EOS 5D Mark II"))
        assertEquals("Canon", e.maker)
        assertEquals("EOS 5D Mark II", e.modelPrefix)
        assertEquals(0, e.black)
        assertEquals(0x3cf0, e.white)
        assertEquals(3, e.colors)
        assertEquals(listOf(4716, 603, -830, -7798, 15474, 2480, -1496, 1937, 6651), ints(e))
    }

    @Test
    fun canonOriginal5dDoesNotMatchMarkRows() {
        // "EOS 5D" row must be found for the plain 5D and has its own saturation level
        val e = assertNotNull(CameraMatrices.find("Canon", "Canon EOS 5D"))
        assertEquals("EOS 5D", e.modelPrefix)
        assertEquals(0xe6c, e.white)
        assertEquals(listOf(6347, -479, -972, -8297, 15954, 2480, -1968, 2131, 7649), ints(e))
    }

    @Test
    fun mostSpecificRowWinsByTableOrder() {
        // "EOS Ra" is listed before "EOS R" so it wins for the Ra, and "EOS R" still matches "EOS R"
        val ra = assertNotNull(CameraMatrices.find("Canon", "Canon EOS Ra"))
        assertEquals("EOS Ra", ra.modelPrefix)
        assertEquals(listOf(22880, -11531, -2223, -2034, 10469, 1809, 316, 1401, 5769), ints(ra))
        val r = assertNotNull(CameraMatrices.find("Canon", "Canon EOS R"))
        assertEquals("EOS R", r.modelPrefix)
        assertEquals(listOf(8293, -1789, -1094, -5025, 12925, 2327, -1199, 2769, 6108), ints(r))
        // Nikon D700 / D70: D70 is a prefix of D700, D700 is listed first
        assertEquals("D700", assertNotNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D700")).modelPrefix)
        assertEquals("D70", assertNotNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D70")).modelPrefix)
        assertEquals("D70", assertNotNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D70s")).modelPrefix)
        // D3 / D3S / D3X / D3000: longer rows are listed before the bare "D3"
        assertEquals("D3S", assertNotNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D3S")).modelPrefix)
        assertEquals("D3000", assertNotNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D3000")).modelPrefix)
        assertEquals("D3", assertNotNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D3")).modelPrefix)
    }

    @Test
    fun nikonD810AndMakerNormalisation() {
        val e = assertNotNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D810"))
        assertEquals("Nikon", e.maker)
        assertEquals(listOf(9369, -3195, -791, -4488, 12430, 2301, -893, 1796, 6872), ints(e))
        // maker lookup is a case-insensitive substring test, prefix matching is case-insensitive too
        assertNotNull(CameraMatrices.find("nikon", "d810"))
        // Nikon D3200 carries a white level
        assertEquals(0xfb9, assertNotNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D3200")).white)
    }

    @Test
    fun sonyAlphaAndFourColourSensor() {
        val a7 = assertNotNull(CameraMatrices.find("SONY", "ILCE-7M3"))
        assertEquals(listOf(7374, -2389, -551, -5435, 13162, 2519, -1006, 1795, 6552), ints(a7))
        // DSC-F828 has a 4-colour (RGBE) sensor: 12 coefficients, the 4th row must be preserved
        val f828 = assertNotNull(CameraMatrices.find("SONY", "DSC-F828"))
        assertEquals(4, f828.colors)
        assertEquals(listOf(7924, -1910, -777, -8226, 15459, 2998, -1517, 2199, 6818, -7242, 11401, 3481), ints(f828))
        assertEquals(3, assertNotNull(CameraMatrices.find("SONY", "DSC-RX100")).colors)
    }

    @Test
    fun fujifilmFinePixPrefixIsRemovedAndTableMatches() {
        val x = assertNotNull(CameraMatrices.find("FUJIFILM", "X-T3"))
        assertEquals("X-T3", x.modelPrefix)
        assertEquals(listOf(13426, -6334, -1177, -4244, 12136, 2371, -580, 1303, 5980), ints(x))
        // X-T30 shares the X-T3 row by prefix
        assertEquals("X-T3", assertNotNull(CameraMatrices.find("FUJIFILM", "X-T30")).modelPrefix)
        val n = CameraMatrices.normalizeMakeModel("FUJIFILM", "FinePix S9500")
        assertEquals("Fujifilm", n.maker)
        assertEquals("S9000", n.model) // alias: S9500 is the S9000 row
    }

    @Test
    fun negativeBlackIsAFallback() {
        val lx3 = assertNotNull(CameraMatrices.find("Panasonic", "DMC-LX3"))
        assertEquals(-15, lx3.black)
        assertEquals(15, lx3.blackLevel)
        assertTrue(lx3.blackIsFallback)
        assertEquals(listOf(8128, -2668, -655, -6134, 13307, 3161, -1782, 2568, 6083), ints(lx3))
        val canon = assertNotNull(CameraMatrices.find("Canon", "Canon EOS 5D Mark II"))
        assertFalse(canon.blackIsFallback)
        assertEquals(0, canon.blackLevel)
    }

    @Test
    fun makeIsRemovedFromModelAndAliasesApply() {
        // Pentax: make detected from "PENTAX Corporation", "PENTAX " removed from the model
        val k1 = assertNotNull(CameraMatrices.find("PENTAX Corporation", "PENTAX K-1"))
        assertEquals("Pentax", k1.maker)
        assertEquals(listOf(8596, -2981, -639, -4202, 12046, 2431, -685, 1424, 6122), ints(k1))
        // "*ist DL2" is an alias of "*istDL2" (listed before the shorter "*istDL")
        assertEquals("*istDL2", assertNotNull(CameraMatrices.find("PENTAX", "PENTAX *ist DL2")).modelPrefix)
        // Leica V-LUX 1 is a Panasonic DMC-FZ50: maker switches to Panasonic
        val vlux = assertNotNull(CameraMatrices.find("LEICA CAMERA AG", "V-LUX 1"))
        assertEquals("Panasonic", vlux.maker)
        assertEquals("DMC-FZ50", vlux.modelPrefix)
        assertEquals(listOf(7906, -2709, -594, -6231, 13351, 3220, -1922, 2631, 6537), ints(vlux))
        // Konica Minolta DYNAX 7D (case-insensitive alias) -> Minolta DG-7D
        val dg7d = assertNotNull(CameraMatrices.find("KONICA MINOLTA", "DYNAX 7D"))
        assertEquals("Minolta", dg7d.maker)
        assertEquals("DG-7D", dg7d.modelPrefix)
        assertEquals(0xffb, dg7d.white)
        // Olympus long-form model names map onto the canonical row ("E-M10 Mark II" -> row "E-M10")
        assertEquals("E-M10", assertNotNull(CameraMatrices.find("OLYMPUS IMAGING CORP.", "E-M10MarkII")).modelPrefix)
        // Olympus C5050Z -> C-5050Z
        assertEquals("C-5050Z", assertNotNull(CameraMatrices.find("OLYMPUS CORPORATION", "C5050Z")).modelPrefix)
    }

    @Test
    fun adjacentStringLiteralsInLibRawAliasTableAreConcatenated() {
        // LibRaw's panalias has "DC-ZS220D" "DC-TX2" (missing comma) which C joins into one string
        val n = CameraMatrices.normalizeMakeModel("Panasonic", "DC-ZS220DDC-TX2")
        assertEquals("DC-ZS200", n.model)
        // ...and therefore the separate names do not alias to the DC-ZS200 row
        assertEquals("DC-TX2", CameraMatrices.normalizeMakeModel("Panasonic", "DC-TX2").model)
    }

    @Test
    fun prefixMatching() {
        // any model beginning with the row prefix is matched, shorter models are not
        assertNotNull(CameraMatrices.find("Canon", "Canon EOS 5D Mark II Something"))
        assertNull(CameraMatrices.find("Canon", "Canon EOS 5"))
        // Canon PowerShot rows
        assertNotNull(CameraMatrices.find("Canon", "Canon PowerShot G9"))
    }

    @Test
    fun unknownCameras() {
        assertNull(CameraMatrices.find("Acme Optics", "Model 9000"))
        assertNull(CameraMatrices.find("", ""))
        assertNull(CameraMatrices.find("Canon", "Canon Completely Unknown Camera 99"))
        assertNull(CameraMatrices.find("Nikon", ""))
        assertNull(CameraMatrices.findNormalized(null, "D810"))
        assertNull(CameraMatrices.findNormalized("Not A Maker", "D810"))
    }

    @Test
    fun makerNormalisationRules() {
        val n = CameraMatrices.normalizeMakeModel("OLYMPUS IMAGING CORP.  ", "E-M1  ")
        assertEquals("Olympus", n.maker)
        assertEquals("Olympus", n.make)
        assertEquals("E-M1", n.model)
        // "OM Digital" cameras are looked up as Olympus
        assertEquals("Olympus", CameraMatrices.normalizeMakeModel("OM Digital Solutions", "OM-1").maker)
        // Phase One has two spellings in LibRaw's maker list, the first one is canonical
        assertEquals("Phase One", CameraMatrices.normalizeMakeModel("Phase One A/S", "IQ140").maker)
        assertEquals("Phase One", CameraMatrices.normalizeMakeModel("PhaseOne", "IQ140").maker)
        assertNotNull(CameraMatrices.find("Phase One A/S", "IQ140"))
        // unknown maker -> maker null, model is left alone apart from trimming
        val u = CameraMatrices.normalizeMakeModel("Foo", "Bar ")
        assertNull(u.maker)
        assertEquals("Bar", u.model)
    }

    /**
     * Matrices LibRaw 0.22.2 (via rawpy `rgb_xyz_matrix`) reports for real sample files, keyed by the raw
     * TIFF `Make` / `Model` strings of those files.  Verifies make/model normalisation end to end.
     */
    @Test
    fun matchesLibRawForRealSampleFiles() {
        data class Case(val make: String, val model: String, val expected: List<Int>)
        val cases = listOf(
            Case("SONY", "ILCE-7SM2", listOf(5838, -1430, -246, -3497, 11477, 2297, -748, 1885, 5778)),
            Case("SONY", "ILCE-7M4", listOf(7460, -2365, -588, -5687, 13442, 2474, -624, 1156, 6584)),
            Case("Canon", "Canon EOS 5D Mark II", listOf(4716, 603, -830, -7798, 15474, 2480, -1496, 1937, 6651)),
            Case("Canon", "Canon EOS 7D", listOf(6844, -996, -856, -3876, 11761, 2396, -593, 1772, 6198)),
            Case("Canon", "Canon EOS R6", listOf(8293, -1611, -1132, -4759, 12711, 2275, -1013, 2415, 5509)),
            Case("NIKON CORPORATION", "NIKON Z fc", listOf(11640, -4829, -1079, -5107, 13006, 2325, -972, 1711, 7380)),
            Case("NIKON CORPORATION", "NIKON D70s", listOf(7732, -2422, -789, -8238, 15884, 2498, -859, 783, 7330)),
            Case("NIKON CORPORATION", "NIKON D700", listOf(8139, -2171, -663, -8747, 16541, 2295, -1925, 2008, 8093)),
            Case("OLYMPUS IMAGING CORP.   ", "E-3             ", listOf(9487, -2875, -1115, -7533, 15606, 2010, -1618, 2100, 7389)),
            Case("FUJIFILM", "X-T2", listOf(11434, -4948, -1210, -3746, 12042, 1903, -666, 1479, 5235)),
            // 4-colour sensor; "E5700" is an alias of "COOLPIX 5700"
            Case("NIKON", "E5700", listOf(-6475, 12496, 2428, 5409, -16, 3180, -5965, 10912, 5866, -177, 9918, 248)),
        )
        for (c in cases) {
            val e = assertNotNull(CameraMatrices.find(c.make, c.model), "${c.make} / ${c.model}")
            assertEquals(c.expected, ints(e), "${c.make} / ${c.model}")
        }
    }
}
