// GENERATED FILE - DO NOT EDIT.  Regenerate with:  python3 tools/gen_camera_matrices.py <LibRaw dir>
//
// Source: LibRaw 0.22.2  (src/tables/colordata.cpp `adobe_coeff`, src/metadata/identify.cpp
// `CorpTable` / `simplify_make_model`, src/metadata/normalize_model.cpp `GetNormalizedModel`).
// LibRaw is Copyright 2008-2025 LibRaw LLC, licensed under LGPL 2.1 or CDDL 1.0 (your choice) and derives
// from dcraw.c by Dave Coffin.  The matrices are mostly from Adobe DNG Converter ("All matrices are from
// Adobe DNG Converter unless otherwise noted" in colordata.cpp).  This project is GPLv3; the LGPL 2.1
// licence of LibRaw permits redistribution under the GPL.  See https://www.libraw.org/
package app.spektralab.raw

/**
 * One row of the LibRaw/dcraw per-camera colour table.
 *
 * @property maker canonical LibRaw maker name ("Canon", "Nikon", "Sony", "Phase One", ...).
 * @property modelPrefix model prefix this row applies to (case-insensitive, empty = every model of the maker).
 * @property black LibRaw `t_black` verbatim: > 0 means "force this black level", < 0 means "use -black only
 *   when the file declares no black level of its own", 0 means "not specified".  See [blackLevel] and
 *   [blackIsFallback].
 * @property white LibRaw `t_maximum` (saturation level), 0 = not specified.
 * @property colors number of colour channels the matrix is for: 3 (RGB) or 4 (e.g. CMYG / RGBE sensors),
 *   or 0 when the row carries no matrix (black / white only).
 * @property xyzToCamera the matrix mapping CIE XYZ (D65) to camera RGB(G), as in DNG `ColorMatrix`, indexed
 *   `[row][col]` with `colors` rows of 3 columns (X, Y, Z).  Rows 0..2 are the first three camera channels;
 *   for 4-colour sensors row 3 is the fourth channel.  `null` when [colors] == 0.
 */
class CameraMatrixEntry(
    val maker: String,
    val modelPrefix: String,
    val black: Int,
    val white: Int,
    val colors: Int,
    val xyzToCamera: Array<DoubleArray>?,
) {
    /** Absolute black level, 0 if unspecified. */
    val blackLevel: Int get() = if (black < 0) -black else black

    /** True when [blackLevel] must only be used if the file itself gives no black level. */
    val blackIsFallback: Boolean get() = black < 0

    override fun toString(): String = "CameraMatrixEntry($maker, \"$modelPrefix\", black=$black, white=$white, colors=$colors)"
}

/**
 * Lookup of per-camera colour data for raw formats that do not carry a colour matrix (CR2, NEF, ARW, ...).
 * Mirrors LibRaw's `adobe_coeff()` including the make / model normalisation LibRaw performs first.
 * Table: 808 entries from LibRaw 0.22.2.
 */
object CameraMatrices {
    /** LibRaw release the table was generated from. */
    const val SOURCE_VERSION: String = "LibRaw 0.22.2"

    /**
     * Find the colour data for a camera given the raw `Make` and `Model` strings (as found in TIFF/EXIF tags).
     * Applies [normalizeMakeModel] then LibRaw's matching: the first table row (in table order, which lists
     * specific models before general ones) whose maker equals the normalised maker and whose prefix is a
     * case-insensitive prefix of the normalised model.  Returns null for unknown cameras.
     */
    fun find(make: String, model: String): CameraMatrixEntry? {
        val n = normalizeMakeModel(make, model)
        return findNormalized(n.maker, n.model)
    }

    /**
     * [find] for a Canon body: LibRaw names Canon cameras by the model id in the maker note (tag 0x0010), so
     * regional names ("EOS Rebel T2i", "EOS Kiss X4") and any Model string find the row of the camera
     * ("EOS 550D"). Falls back to [find] when the id is 0 or unknown.
     */
    fun findCanon(uniqueId: Long, make: String, model: String): CameraMatrixEntry? {
        val n = normalizeMakeModel(make, model)
        val byId = canonModelName(uniqueId)
        if (byId != null && n.model != "EOS Ra") findNormalized("Canon", byId)?.let { return it }
        return findNormalized(n.maker, n.model)
    }

    /** LibRaw GetNormalizedModel `unique[]`: the model name for a Canon maker-note model id, null if unknown. */
    fun canonModelName(uniqueId: Long): String? =
        if (uniqueId == 0L || uniqueId == 0x80000520L || uniqueId == 0x80000560L) null else CANON_MODEL_BY_ID[uniqueId]

    /** Lookup with an already normalised canonical maker name and model (no normalisation is applied). */
    fun findNormalized(maker: String?, model: String): CameraMatrixEntry? {
        if (maker == null) return null
        val list = byMaker[maker] ?: return null
        for (e in list) {
            if (e.modelPrefix.isEmpty() || startsWithIgnoreAscii(model, e.modelPrefix)) return e
        }
        return null
    }

    /** Number of rows in the table. */
    val size: Int get() = entries.size

    /** All rows in LibRaw table order. */
    val all: List<CameraMatrixEntry> get() = entries

    /** Result of [normalizeMakeModel]; [maker] is the canonical LibRaw maker name or null if the maker is unknown. */
    class Normalized(val maker: String?, val make: String, val model: String)

    /**
     * Reproduces the string-only part of LibRaw's identification: `simplify_make_model()` (maker detection
     * by case-insensitive substring against the maker list, removal of the maker from the model, FinePix /
     * "Digital Camera" prefixes, ...) followed by the alias handling of `GetNormalizedModel()` (e.g. Nikon
     * "E990" -> "COOLPIX 990", Panasonic/Leica badge twins, Phase One / Mamiya / Leaf back aliases).
     * Parts of GetNormalizedModel that need information from inside the file (Canon / Sony / Olympus
     * maker-note camera ids, Hasselblad body parsing, Ricoh GXR lens modules) are not reproduced.
     */
    fun normalizeMakeModel(rawMake: String, rawModel: String): Normalized {
        var make = cString(rawMake)
        var model = cString(rawModel)
        // --- simplify_make_model ---------------------------------------------------------------
        var maker: String? = null
        for (name in CORP_NAMES) {
            if (containsIgnoreAscii(make, name)) { maker = firstCorpName(name); break }
        }
        if (maker == "HMD Global" && startsWithIgnoreAscii(model, "Nokia")) maker = "Nokia"
        else if (maker == "JK Imaging" && startsWithIgnoreAscii(model, "Kodak")) maker = "Kodak"
        else if (maker == "Ricoh" && startsWithIgnoreAscii(model, "PENTAX")) maker = "Pentax"
        if (maker != null) make = maker
        if (maker == "Kodak" || maker == "Leica") {
            var cut = indexOfIgnoreAscii(model, " DIGITAL CAMERA")
            if (cut < 0) cut = model.indexOf("FILE VERSION")
            if (cut >= 0) model = model.substring(0, cut)
        }
        make = make.trimEnd { isAsciiSpace(it) }
        model = model.trimEnd { isAsciiSpace(it) }
        if (startsWithIgnoreAscii(model, make) && model.length > make.length && model[make.length] == ' ') {
            model = model.substring(make.length + 1)
        }
        if (maker == "Fujifilm" && model.startsWith("FinePix")) {
            model = model.substring(7)
            if (model.startsWith(" ")) model = model.substring(1)
        } else if ((maker == "Kodak" || maker == "Konica") && model.startsWith("Digital Camera ")) {
            model = model.substring(15)
        }

        // --- GetNormalizedModel (string-only part) -----------------------------------------------
        if (maker == "OM Digital") maker = "Olympus"
        else if (maker == "Canon" && (model.startsWith("EOS D2000") || model.startsWith("EOS D6000") ||
                model.startsWith("EOSDCS"))) maker = "Kodak"
        else if (maker == "Photo Control" && startsWithIgnoreAscii(model, "Camerz ZDS 14")) maker = "Kodak"
        if (maker == "Kodak") {
            if (charAt0(model, 6) == ' ' && (model.startsWith("DCS4") || model.startsWith("NC2000"))) model = model.substring(0, 6)
            val c6 = charAt0(model, 6)
            if (c6 != 'A' && c6 != 'I' && c6 != 'M' && model.startsWith("NC2000")) model = model.substring(0, 6)
        }
        var norm = model
        when (maker) {
            "Fujifilm" -> norm = aliasOf(FUJI_ALIAS, model, ignoreCase = false)?.orig ?: norm
            "Mamiya" -> {
                aliasOf(PHASE1_ALIAS, model, ignoreCase = false, checkOrig = false)?.let { if (it.viaAlias) { maker = "Phase One"; norm = it.orig } }
                aliasOf(LEAF_ALIAS, model, ignoreCase = false, checkOrig = false)?.let { if (it.viaAlias) { maker = "Leaf"; norm = it.orig } }
            }
            "Leaf" -> {
                aliasOf(LEAF_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }
                val p = norm.indexOf('(')
                if (p >= 0) norm = norm.substring(0, p)
            }
            "Minolta", "Konica" -> {
                if (maker == "Konica" && startsWithIgnoreAscii(model, "DiMAGE")) {
                    maker = "Minolta"; make = "Minolta"
                } else {
                    val a = aliasOf(KONICA_MINOLTA_ALIAS, model, ignoreCase = true, aliasIgnoreCase = true)
                    if (a != null) { maker = "Minolta"; make = "Minolta"; if (a.viaAlias) norm = a.orig }
                }
            }
            "Nikon" -> norm = aliasOf(NIKON_ALIAS, model, ignoreCase = false)?.orig ?: norm
            "Olympus" -> norm = aliasOf(OLYMPUS_ALIAS, model, ignoreCase = false)?.orig ?: norm
            "Panasonic", "Leica", "Yuneec" -> aliasOf(PANASONIC_ALIAS, model, ignoreCase = false)?.let {
                if (it.viaAlias) { maker = "Panasonic"; norm = it.orig }
            }
            "Pentax" -> {
                aliasOf(PENTAX_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }
                if (model.startsWith("GR")) { maker = "Ricoh"; make = "Ricoh" }
            }
            "Phase One" -> aliasOf(PHASE1_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }
            "Samsung" -> {
                val wb = when {
                    model.contains("WB5500") || model.contains("HZ50W") -> "WB5500"
                    model.contains("WB5000") || model.contains("HZ25W") -> "WB5000"
                    model.contains("WB550") || model.contains("HZ15W") -> "WB550"
                    model.contains("WB500") || model.contains("HZ10W") -> "WB500"
                    else -> null
                }
                if (wb != null) { model = wb; norm = wb }
                else aliasOf(SAMSUNG_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }
            }
            "Kodak" -> {
                norm = removeSubstrIgnoreAscii(norm, "EasyShare")
                norm = removeSubstrIgnoreAscii(norm, "ZOOM")
                norm = collapseSpaces(norm)
                aliasOf(KODAK_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }
                when {
                    model.contains("DC25") -> { model = "DC25"; norm = model }
                    model == "40" -> { model = "DC40"; norm = model }
                    model.contains("DC50") -> { model = "DC50"; norm = model }
                    model.contains("DC120") -> { model = "DC120"; norm = model }
                }
            }
        }
        return Normalized(maker, make, norm)
    }

    // ------------------------------------------------------------------------------------------
    // implementation

    private class AliasHit(val orig: String, val viaAlias: Boolean)

    /**
     * Walks a LibRaw alias table: entries starting with '@' name a canonical model, the entries after it are
     * its aliases.  Returns null when no alias matches (also when [model] equals a canonical name and
     * [checkOrig] is set, which stops the scan exactly like LibRaw's `break`), or a hit otherwise.
     * For Konica/Minolta a hit on the canonical name itself is reported with viaAlias = false.
     */
    private fun aliasOf(
        table: Array<String>, model: String, ignoreCase: Boolean,
        checkOrig: Boolean = true, aliasIgnoreCase: Boolean = false,
    ): AliasHit? {
        var orig = ""
        for (s in table) {
            if (s.startsWith("@")) {
                orig = s.substring(1)
                if (checkOrig && model == orig) return if (ignoreCase) AliasHit(orig, false) else null
            } else if (if (aliasIgnoreCase) equalsIgnoreAscii(model, s) else model == s) {
                return AliasHit(orig, true)
            }
        }
        return null
    }

    private fun isAsciiSpace(c: Char) = c == ' ' || c in '\t'..'\r'

    private fun charAt0(s: String, i: Int): Char = if (i < s.length) s[i] else '\u0000'

    /** C strings stop at the first NUL. */
    private fun cString(s: String): String { val z = s.indexOf('\u0000'); return if (z >= 0) s.substring(0, z) else s }

    private fun lowerAscii(c: Char): Char = if (c in 'A'..'Z') (c.code + 32).toChar() else c

    private fun regionEqualsIgnoreAscii(s: String, at: Int, p: String): Boolean {
        if (at < 0 || at + p.length > s.length) return false
        for (i in p.indices) if (lowerAscii(s[at + i]) != lowerAscii(p[i])) return false
        return true
    }

    /** strncasecmp(s, prefix, prefix.length) == 0 */
    internal fun startsWithIgnoreAscii(s: String, prefix: String): Boolean = regionEqualsIgnoreAscii(s, 0, prefix)

    private fun equalsIgnoreAscii(a: String, b: String): Boolean = a.length == b.length && regionEqualsIgnoreAscii(a, 0, b)

    /** strcasestr */
    private fun indexOfIgnoreAscii(s: String, p: String): Int {
        for (i in 0..s.length - p.length) if (regionEqualsIgnoreAscii(s, i, p)) return i
        return -1
    }

    private fun containsIgnoreAscii(s: String, p: String) = indexOfIgnoreAscii(s, p) >= 0

    /** LibRaw remove_caseSubstr(): blank out every occurrence, then trim. */
    private fun removeSubstrIgnoreAscii(s: String, p: String): String {
        val sb = StringBuilder(s)
        var at = indexOfIgnoreAscii(sb.toString(), p)
        while (at >= 0) {
            for (i in at until at + p.length) sb.setCharAt(i, ' ')
            at = indexOfIgnoreAscii(sb.toString(), p)
        }
        return sb.toString().trim { isAsciiSpace(it) }
    }

    /** LibRaw removeExcessiveSpaces(): drop leading spaces, collapse runs of spaces, drop one trailing space. */
    private fun collapseSpaces(s: String): String {
        val sb = StringBuilder()
        var prevSpace = false
        var started = false
        for (c in s) {
            if (c == ' ') {
                if (started && !prevSpace) { sb.append(c); prevSpace = true }
            } else { sb.append(c); prevSpace = false; started = true }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    private fun firstCorpName(name: String): String = CORP_FIRST_NAME[name] ?: name

    // ---- data extracted from LibRaw 0.22.2 ------------------------------------------------------

    /** LibRaw `CorpTable` names in match order (maker detection is a case-insensitive substring test). */
    private val CORP_NAMES: Array<String> = arrayOf(
        "AgfaPhoto",
        "Apple",
        "Broadcom",
        "Canon",
        "Casio",
        "CINE",
        "Epson",
        "Fujifilm",
        "Mamiya",
        "Motorola",
        "Kodak",
        "Konica",
        "Minolta",
        "Leica",
        "Nikon",
        "Nokia",
        "Olympus",
        "OM Digital",
        "Ricoh",
        "Pentax",
        "Phase One",
        "PhaseOne",
        "Samsung",
        "Sigma",
        "Sinar",
        "Sony",
        "YI",
        "Alcatel",
        "Aptina",
        "AVT",
        "Baumer",
        "Clauss",
        "Contax",
        "Creative",
        "DJI",
        "Foculus",
        "Generic",
        "Gione",
        "GITUP",
        "Hasselblad",
        "HTC",
        "I_Mobile",
        "Imacon",
        "ISG",
        "JK Imaging",
        "Leaf",
        "Lenovo",
        "LG",
        "Logitech",
        "Matrix",
        "Meizu",
        "Micron",
        "NGM",
        "OmniVison",
        "Panasonic",
        "Photron",
        "Pixelink",
        "Polaroid",
        "Rollei",
        "RoverShot",
        "SMaL",
        "ST Micro",
        "THL",
        "Xiaomi",
        "Xiayi",
        "Yuneec",
        "DxO",
        "Red",
        "Photo Control",
        "Google",
        "GoPro",
        "Parrot",
        "Zeiss",
        "OnePlus",
        "Vivo",
        "HMD Global",
        "Huawei",
        "RaspberryPi",
    )

    /** Several CorpTable names map to one maker id ("Phase One" / "PhaseOne"); the first one is canonical. */
    private val CORP_FIRST_NAME: Map<String, String> = HashMap<String, String>().also { m ->
        m["PhaseOne"] = "Phase One"
    }

    private val FUJI_ALIAS: Array<String> = arrayOf(
        "@DBP for GX680",
        "DX-2000",
        "@F500EXR",
        "F505EXR",
        "@F600EXR",
        "F605EXR",
        "@F770EXR",
        "F775EXR",
        "@HS10",
        "HS10 HS11",
        "@HS20EXR",
        "HS22EXR",
        "@HS30EXR",
        "HS33EXR",
        "HS35EXR",
        "@S5100",
        "S5500",
        "@S5200",
        "S5600",
        "@S6000fd",
        "S6500fd",
        "@S9000",
        "S9500",
        "@S9100",
        "S9600",
        "@S200EXR",
        "S205EXR",
        "@X-T1 IR",
        "X-T1IR",
        "@GFX 100 II",
        "GFX100 II",
        "@GFX 100S II",
        "GFX100S II",
        "@GFX 100S",
        "GFX100S",
        "@GFX 50S II",
        "GFX50S II",
    )

    private val KODAK_ALIAS: Array<String> = arrayOf(
        "@DCS Pro 14N",
        "Camerz ZDS 14",
        "@DCS720X",
        "SCS2000",
        "@DCS520C",
        "EOS D2000C",
        "EOS D2000",
        "@DCS560C",
        "EOS D6000C",
        "EOS D6000",
        "@DCS460M",
        "DCS460A",
        "@DCS460",
        "DCS460C",
        "DCS460D",
        "@DCS465",
        "DCS465C",
        "DCS465D",
        "@EOSDCS1",
        "EOSDCS1B",
        "EOSDCS1C",
        "@EOSDCS3",
        "EOSDCS3B",
        "EOSDCS3C",
    )

    private val LEAF_ALIAS: Array<String> = arrayOf(
        "@Aptus-II 5",
        "DM22",
        "@Aptus-II 6",
        "DM28",
        "@Aptus-II 7",
        "DM33",
        "@Aptus-II 8",
        "DM40",
        "@Aptus-II 10",
        "DM56",
    )

    private val KONICA_MINOLTA_ALIAS: Array<String> = arrayOf(
        "@DG-5D",
        "DYNAX 5D",
        "MAXXUM 5D",
        "ALPHA-5 DIGITAL",
        "ALPHA SWEET DIGITAL",
        "@DG-7D",
        "DYNAX 7D",
        "MAXXUM 7D",
        "ALPHA-7 DIGITAL",
    )

    private val NIKON_ALIAS: Array<String> = arrayOf(
        "@COOLPIX 2100",
        "E2100",
        "@COOLPIX 2500",
        "E2500",
        "@COOLPIX 3200",
        "E3200",
        "@COOLPIX 3700",
        "E3700",
        "@COOLPIX 4300",
        "E4300",
        "@COOLPIX 4500",
        "E4500",
        "@COOLPIX 5000",
        "E5000",
        "@COOLPIX 5400",
        "E5400",
        "@COOLPIX 5700",
        "E5700",
        "@COOLPIX 8400",
        "E8400",
        "@COOLPIX 8700",
        "E8700",
        "@COOLPIX 8800",
        "E8800",
        "@COOLPIX 700",
        "E700",
        "@COOLPIX 800",
        "E800",
        "@COOLPIX 880",
        "E880",
        "@COOLPIX 900",
        "E900",
        "@COOLPIX 950",
        "E950",
        "@COOLPIX 990",
        "E990",
        "@COOLPIX 995",
        "E995",
        "@COOLPIX P7700",
        "COOLPIX Deneb",
        "@COOLPIX P7800",
        "COOLPIX Kalon",
    )

    private val OLYMPUS_ALIAS: Array<String> = arrayOf(
        "@AIR A01",
        "AIR-A01",
        "@C-3030Z",
        "C3030Z",
        "@C-5050Z",
        "C5050Z",
        "@C-5060WZ",
        "C5060WZ",
        "@C-7000Z",
        "C7000Z",
        "C70Z",
        "@C-7070WZ",
        "C7070WZ",
        "@C-8080WZ",
        "C8080WZ",
        "@C-350Z",
        "C350Z",
        "X200",
        "D560Z",
        "@C-740UZ",
        "C740UZ",
        "@C-770UZ",
        "C770UZ",
        "@E-20",
        "E-20N",
        "E-20P",
        "@E-M10 Mark II",
        "E-M10MarkII",
        "E-M10_M2",
        "piX 5oo",
        "@E-M10 Mark III",
        "E-M10MarkIII",
        "E-M10_M3",
        "@E-M10 Mark IV",
        "E-M10MarkIV",
        "E-M10_M4",
        "@E-M1 Mark II",
        "E-M1MarkII",
        "E-M1_M2",
        "@E-M1 Mark III",
        "E-M1MarkIII",
        "E-M1_M3",
        "@E-M5 Mark II",
        "E-M5MarkII",
        "E-M5_M2",
        "@E-M5 Mark III",
        "E-M5MarkIII",
        "E-M5_M3",
        "@OM-1 Mark II",
        "OM-1MarkII",
        "OM-1_M2",
        "@SH-2",
        "SH-3",
        "@SP-310",
        "SP310",
        "@SP-320",
        "SP320",
        "@SP-350",
        "SP350",
        "@SP-500UZ",
        "SP500UZ",
        "@SP-510UZ",
        "SP510UZ",
        "@SP-550UZ",
        "SP550UZ",
        "@SP-560UZ",
        "SP560UZ",
        "@SP-565UZ",
        "SP565UZ",
        "@SP-570UZ",
        "SP570UZ",
        "@Stylus 1",
        "STYLUS1",
        "STYLUS1s",
    )

    private val PANASONIC_ALIAS: Array<String> = arrayOf(
        "@DMC-FX150",
        "DMC-FX180",
        "@DC-FZ1000M2",
        "DC-FZ10002",
        "V-Lux 5",
        "@DMC-FZ1000",
        "V-LUX (Typ 114)",
        "@DMC-FZ2500",
        "DMC-FZ2000",
        "DMC-FZH1",
        "@DMC-FZ100",
        "V-LUX 2",
        "@DMC-FZ150",
        "V-LUX 3",
        "@DMC-FZ200",
        "V-LUX 4",
        "@DMC-FZ300",
        "DMC-FZ330",
        "@DMC-FZ35",
        "DMC-FZ38",
        "@DMC-FZ40",
        "DMC-FZ42",
        "DMC-FZ45",
        "DC-FZ40",
        "DC-FZ42",
        "DC-FZ45",
        "@DMC-FZ50",
        "V-LUX 1",
        "V-LUX1",
        "@DMC-FZ70",
        "DMC-FZ72",
        "@DC-FZ80",
        "DC-FZ81",
        "DC-FZ82",
        "DC-FZ83",
        "DC-FZ85",
        "@DMC-LC1",
        "DIGILUX 2",
        "Digilux 2",
        "DIGILUX2",
        "@DMC-LF1",
        "C (Typ 112)",
        "@DC-LX100M2",
        "D-Lux 7",
        "@DMC-LX100",
        "D-LUX (Typ 109)",
        "D-Lux (Typ 109)",
        "@DMC-LX1",
        "D-Lux2",
        "D-LUX2",
        "D-LUX 2",
        "@DMC-LX2",
        "D-LUX 3",
        "D-LUX3",
        "@DMC-LX3",
        "D-LUX 4",
        "@DMC-LX5",
        "D-LUX 5",
        "@DMC-LX7",
        "D-LUX 6",
        "@DMC-LX9",
        "DMC-LX10",
        "DMC-LX15",
        "@DMC-ZS100",
        "DMC-ZS110",
        "DMC-TZ100",
        "DMC-TZ101",
        "DMC-TZ110",
        "DMC-TX1",
        "@DC-ZS200",
        "DC-ZS220",
        "DC-TZ200",
        "DC-TZ202",
        "DC-TZ220",
        "DC-TZ200D",
        "DC-TZ202D",
        "DC-TZ220D",
        "DC-ZS200D",
        "DC-ZS220DDC-TX2",
        "C-Lux",
        "CAM-DC25",
        "@DMC-ZS40",
        "DMC-TZ60",
        "DMC-TZ61",
        "@DMC-ZS50",
        "DMC-TZ70",
        "DMC-TZ71",
        "@DMC-ZS60",
        "DMC-TZ80",
        "DMC-TZ81",
        "DMC-TZ82",
        "DMC-TZ85",
        "@DC-ZS70",
        "DC-TZ90",
        "DC-TZ91",
        "DC-TZ92",
        "DC-TZ93",
        "@DC-ZS80",
        "DC-TZ95",
        "DC-TZ96",
        "DC-TZ97",
        "@DC-G100",
        "DC-G110",
        "@DC-G99",
        "DC-G90",
        "DC-G91",
        "DC-G95",
        "@DMC-G7",
        "DMC-G70",
        "@DMC-G8",
        "DMC-G80",
        "DMC-G81",
        "DMC-G85",
        "@DMC-GH4",
        "AG-GH4",
        "CGO4",
        "@DC-GF10",
        "DC-GF90",
        "DC-GX880",
        "@DC-GF9",
        "DC-GX850",
        "DC-GX800",
        "@DMC-GM1",
        "DMC-GM1S",
        "@DMC-GX85",
        "DMC-GX80",
        "DMC-GX7MK2",
        "@DC-GX9",
        "DC-GX7MK3",
        "@DMC-L1",
        "DIGILUX 3",
        "DIGILUX3",
    )

    private val PHASE1_ALIAS: Array<String> = arrayOf(
        "@H20",
        "H 20",
        "@H25",
        "H 25",
        "@P20+",
        "P 20+",
        "@P20",
        "P 20",
        "@P21+",
        "P 21+",
        "M18",
        "@P21",
        "P 21",
        "@P25+",
        "P 25+",
        "M22",
        "@P25",
        "P 25",
        "@P30+",
        "P 30+",
        "M31",
        "@P30",
        "P 30",
        "@P40+",
        "P 40+",
        "@P40",
        "P 40",
        "@P45+",
        "P 45+",
        "@P45",
        "P 45",
        "@P65+",
        "P 65+",
        "@P65",
        "P 65",
    )

    private val PENTAX_ALIAS: Array<String> = arrayOf(
        "@*istDL2",
        "*ist DL2",
        "GX-1L",
        "@*istDS2",
        "*ist DS2",
        "GX-1S",
        "@*istDL",
        "*ist DL",
        "@*istDS",
        "*ist DS",
        "@*istD",
        "*ist D",
        "@K10D",
        "GX10",
        "GX-10",
        "@K20D",
        "GX20",
        "GX-20",
        "@K-m",
        "K2000",
    )

    private val SAMSUNG_ALIAS: Array<String> = arrayOf(
        "@EX1",
        "TL500",
        "@NX U",
        "EK-GN100",
        "EK-GN110",
        "EK-GN120",
        "EK-KN120",
        "Galaxy NX",
        "@NX mini",
        "NXF1",
        "@WB2000",
        "TL350",
    )

    /** Canon maker-note model id -> LibRaw model name (GetNormalizedModel `unique[]`). */
    private val CANON_MODEL_BY_ID: Map<Long, String> = mapOf(
        0x00000412L to "EOS M50",
        0x00000811L to "EOS M6 Mark II",
        0x00000812L to "EOS M200",
        0x01140000L to "EOS D30",
        0x01668000L to "EOS D60",
        0x03740000L to "EOS M3",
        0x03840000L to "EOS M10",
        0x03940000L to "EOS M5",
        0x03980000L to "EOS M100",
        0x04070000L to "EOS M6",
        0x40000218L to "EOS R5 C",
        0x80000001L to "EOS-1D",
        0x80000167L to "EOS-1Ds",
        0x80000168L to "EOS 10D",
        0x80000169L to "EOS-1D Mark III",
        0x80000170L to "EOS 300D",
        0x80000174L to "EOS-1D Mark II",
        0x80000175L to "EOS 20D",
        0x80000176L to "EOS 450D",
        0x80000188L to "EOS-1Ds Mark II",
        0x80000189L to "EOS 350D",
        0x80000190L to "EOS 40D",
        0x80000213L to "EOS 5D",
        0x80000215L to "EOS-1Ds Mark III",
        0x80000218L to "EOS 5D Mark II",
        0x80000232L to "EOS-1D Mark II N",
        0x80000234L to "EOS 30D",
        0x80000236L to "EOS 400D",
        0x80000250L to "EOS 7D",
        0x80000252L to "EOS 500D",
        0x80000254L to "EOS 1000D",
        0x80000261L to "EOS 50D",
        0x80000269L to "EOS-1D X",
        0x80000270L to "EOS 550D",
        0x80000281L to "EOS-1D Mark IV",
        0x80000285L to "EOS 5D Mark III",
        0x80000286L to "EOS 600D",
        0x80000287L to "EOS 60D",
        0x80000288L to "EOS 1100D",
        0x80000289L to "EOS 7D Mark II",
        0x80000301L to "EOS 650D",
        0x80000302L to "EOS 6D",
        0x80000324L to "EOS-1D C",
        0x80000325L to "EOS 70D",
        0x80000326L to "EOS 700D",
        0x80000327L to "EOS 1200D",
        0x80000328L to "EOS-1D X Mark II",
        0x80000331L to "EOS M",
        0x80000346L to "EOS 100D",
        0x80000347L to "EOS 760D",
        0x80000349L to "EOS 5D Mark IV",
        0x80000350L to "EOS 80D",
        0x80000355L to "EOS M2",
        0x80000382L to "EOS 5DS",
        0x80000393L to "EOS 750D",
        0x80000401L to "EOS 5DS R",
        0x80000404L to "EOS 1300D",
        0x80000405L to "EOS 800D",
        0x80000406L to "EOS 6D Mark II",
        0x80000408L to "EOS 77D",
        0x80000417L to "EOS 200D",
        0x80000421L to "EOS R5",
        0x80000422L to "EOS 3000D",
        0x80000424L to "EOS R",
        0x80000428L to "EOS-1D X Mark III",
        0x80000432L to "EOS 1500D",
        0x80000433L to "EOS RP",
        0x80000435L to "EOS 850D",
        0x80000436L to "EOS 250D",
        0x80000437L to "EOS 90D",
        0x80000450L to "EOS R3",
        0x80000453L to "EOS R6",
        0x80000464L to "EOS R7",
        0x80000465L to "EOS R10",
        0x80000468L to "EOS M50 Mark II",
        0x80000480L to "EOS R50",
        0x80000481L to "EOS R6 Mark II",
        0x80000487L to "EOS R8",
        0x80000495L to "EOS R1",
        0x80000496L to "EOS R5 Mark II",
        0x80000498L to "EOS R100",
    )

    // Table rows are packed into string constants (one row per line, tab separated:
    // maker, model prefix, black, white, 12 matrix values x10000 row-major, 4th row all zero for RGB)
    // so that no single method or constant approaches the JVM 64 KB limits.
    private val CHUNKS: Array<String> = arrayOf(
        "AgfaPhoto\tDC-833m\t0\t0\t11438,-3762,-1115,-2409,9914,2497,-1227,2295,5300,0,0,0\nApple\tQuickTake\t0\t0\t21392,-5653,-3353,2406,8010,-415,7166,1427,2078,0,0,0\nBroadcom\tRPi IMX219\t66\t1023\t5302,1083,-728,-5320,14112,1699,-863,2371,5136,0,0,0\nBroadcom\tRPi OV5647\t16\t1023\t12782,-4059,-379,-478,9066,1413,1340,1513,5176,0,0,0\nBroadcom\tPi\t16\t1023\t12782,-4059,-379,-478,9066,1413,1340,1513,5176,0,0,0\nCanon\tEOS D30\t0\t0\t9900,-2771,-1324,-7072,14229,3140,-2790,3344,8861,0,0,0\nCanon\tEOS D60\t0\t4000\t6211,-1358,-896,-8557,15766,3012,-3001,3507,8567,0,0,0\nCanon\tEOS 5DS\t0\t15510\t6250,-711,-808,-5153,12794,2636,-1249,2198,5610,0,0,0\nCanon\tEOS 5D Mark IV\t0\t0\t6446,-366,-864,-4436,12204,2513,-952,2496,6348,0,0,0\nCanon\tEOS 5D Mark III\t0\t15488\t6722,-635,-963,-4287,12460,2028,-908,2162,5668,0,0,0\nCanon\tEOS 5D Mark II\t0\t15600\t4716,603,-830,-7798,15474,2480,-1496,1937,6651,0,0,0\nCanon\tEOS 5D\t0\t3692\t6347,-479,-972,-8297,15954,2480,-1968,2131,7649,0,0,0\nCanon\tEOS 6D Mark II\t0\t14558\t6875,-970,-932,-4691,12459,2501,-874,1953,5809,0,0,0\nCanon\tEOS 6D\t0\t15490\t7034,-804,-1014,-4420,12564,2058,-851,1994,5758,0,0,0\nCanon\tEOS 77D\t0\t0\t7377,-742,-998,-4235,11981,2549,-673,1918,5538,0,0,0\nCanon\tEOS 7D Mark II\t0\t13584\t7268,-1082,-969,-4186,11839,2663,-825,2029,5839,0,0,0\nCanon\tEOS 7D\t0\t13584\t6844,-996,-856,-3876,11761,2396,-593,1772,6198,0,0,0\nCanon\tEOS 850D\t0\t0\t9079,-1923,-1236,-4677,12454,2492,-922,2319,5565,0,0,0\nCanon\tEOS 800D\t0\t0\t6970,-512,-968,-4425,12161,2553,-739,1982,5601,0,0,0\nCanon\tEOS 80D\t0\t0\t7457,-671,-937,-4849,12495,2643,-1213,2354,5492,0,0,0\nCanon\tEOS 10D\t0\t4000\t8250,-2044,-1127,-8092,15606,2664,-2893,3453,8348,0,0,0\nCanon\tEOS 250D\t0\t0\t9079,-1923,-1236,-4677,12454,2492,-922,2319,5565,0,0,0\nCanon\tEOS 200D\t0\t0\t7377,-742,-998,-4235,11981,2549,-673,1918,5538,0,0,0\nCanon\tEOS 20Da\t0\t0\t14155,-5065,-1382,-6550,14633,2039,-1623,1824,6561,0,0,0\nCanon\tEOS 20D\t0\t4095\t6599,-537,-891,-8071,15783,2424,-1983,2234,7462,0,0,0\nCanon\tEOS 30D\t0\t0\t6257,-303,-1000,-7880,15621,2396,-1714,1904,7046,0,0,0\nCanon\tEOS 40D\t0\t16224\t6071,-747,-856,-7653,15365,2441,-2025,2553,7315,0,0,0\nCanon\tEOS 50D\t0\t15763\t4920,616,-593,-6493,13964,2784,-1774,3178,7005,0,0,0\nCanon\tEOS 60Da\t0\t12279\t17492,-7240,-2023,-1791,10323,1701,-186,1329,5406,0,0,0\nCanon\tEOS 60D\t0\t12279\t6719,-994,-925,-4408,12426,2211,-887,2129,6051,0,0,0\nCanon\tEOS 70D\t0\t15303\t7034,-804,-1014,-4420,12564,2058,-851,1994,5758,0,0,0\nCanon\tEOS 100D\t0\t13583\t6602,-841,-939,-4472,12458,2247,-975,2039,6148,0,0,0\nCanon\tEOS 300D\t0\t4000\t8250,-2044,-1127,-8092,15606,2664,-2893,3453,8348,0,0,0\nCanon\tEOS 350D\t0\t4095\t6018,-617,-965,-8645,15881,2975,-1530,1719,7642,0,0,0\nCanon\tEOS 3000D\t0\t0\t6939,-1016,-866,-4428,12473,2177,-1175,2178,6162,0,0,0\nCanon\tEOS 400D\t0\t3726\t7054,-1501,-990,-8156,15544,2812,-1278,1414,7796,0,0,0\nCanon\tEOS 450D\t0\t14605\t5784,-262,-821,-7539,15064,2672,-1982,2681,7427,0,0,0\nCanon\tEOS 500D\t0\t13433\t4763,712,-646,-6821,14399,2640,-1921,3276,6561,0,0,0\nCanon\tEOS 550D\t0\t15831\t6941,-1164,-857,-3825,11597,2534,-416,1540,6039,0,0,0\nCanon\tEOS 600D\t0\t13584\t6461,-907,-882,-4300,12184,2378,-819,1944,5931,0,0,0",
        "Canon\tEOS 650D\t0\t13645\t6602,-841,-939,-4472,12458,2247,-975,2039,6148,0,0,0\nCanon\tEOS 750D\t0\t15360\t6362,-823,-847,-4426,12109,2616,-743,1857,5635,0,0,0\nCanon\tEOS 760D\t0\t15360\t6362,-823,-847,-4426,12109,2616,-743,1857,5635,0,0,0\nCanon\tEOS 700D\t0\t15360\t6602,-841,-939,-4472,12458,2247,-975,2039,6148,0,0,0\nCanon\tEOS 90D\t0\t0\t11498,-3759,-1516,-5073,12954,2349,-892,1867,6118,0,0,0\nCanon\tEOS 1000D\t0\t3651\t6771,-1139,-977,-7818,15123,2928,-1244,1437,7533,0,0,0\nCanon\tEOS 1100D\t0\t13584\t6444,-904,-893,-4563,12308,2535,-903,2016,6728,0,0,0\nCanon\tEOS 1200D\t0\t14274\t6461,-907,-882,-4300,12184,2378,-819,1944,5931,0,0,0\nCanon\tEOS 1300D\t0\t14274\t6939,-1016,-866,-4428,12473,2177,-1175,2178,6162,0,0,0\nCanon\tEOS 1500D\t0\t0\t8300,-2110,-1120,-4917,12694,2482,-938,2141,5666,0,0,0\nCanon\tEOS RP\t0\t0\t8608,-2097,-1178,-5425,13265,2383,-1149,2238,5680,0,0,0\nCanon\tEOS R3\t0\t0\t9423,-2839,-1195,-4532,12377,2415,-483,1374,5276,0,0,0\nCanon\tEOS R50\t0\t0\t9269,-2012,-1107,-3990,11762,2527,-569,2093,4913,0,0,0\nCanon\tEOS R100\t0\t0\t8230,-1515,-1032,-4179,12005,2454,-649,2076,4711,0,0,0\nCanon\tEOS R5 Mark II\t0\t0\t9396,-2598,-1207,-4408,12296,2369,-505,1575,6077,0,0,0\nCanon\tEOS R5\t0\t0\t9766,-2953,-1254,-4276,12116,2433,-437,1336,5131,0,0,0\nCanon\tEOS R6 Mark II\t0\t0\t9539,-2795,-1224,-4175,11998,2458,-465,1755,6048,0,0,0\nCanon\tEOS R6\t0\t0\t8293,-1611,-1132,-4759,12711,2275,-1013,2415,5509,0,0,0\nCanon\tEOS R7\t0\t0\t10424,-3138,-1300,-4221,11938,2584,-547,1658,6183,0,0,0\nCanon\tEOS R8\t0\t0\t9539,-2795,-1224,-4175,11998,2458,-465,1755,6048,0,0,0\nCanon\tEOS R10\t0\t0\t9269,-2012,-1107,-3990,11762,2527,-569,2093,4913,0,0,0\nCanon\tEOS R1\t0\t0\t9020,-2275,-1209,-4916,12856,2284,-923,1953,5928,0,0,0\nCanon\tEOS Ra\t0\t0\t22880,-11531,-2223,-2034,10469,1809,316,1401,5769,0,0,0\nCanon\tEOS R\t0\t0\t8293,-1789,-1094,-5025,12925,2327,-1199,2769,6108,0,0,0\nCanon\tEOS M6 Mark II\t0\t0\t11498,-3759,-1516,-5073,12954,2349,-892,1867,6118,0,0,0\nCanon\tEOS M6\t0\t0\t8532,-701,-1167,-4095,11879,2508,-797,2424,7010,0,0,0\nCanon\tEOS M50 Mark II\t0\t0\t10463,-2173,-1437,-4856,12635,2482,-1216,2915,7237,0,0,0\nCanon\tEOS M50\t0\t0\t8532,-701,-1167,-4095,11879,2508,-797,2424,7010,0,0,0\nCanon\tEOS M5\t0\t0\t8532,-701,-1167,-4095,11879,2508,-797,2424,7010,0,0,0\nCanon\tEOS M3\t0\t0\t6362,-823,-847,-4426,12109,2616,-743,1857,5635,0,0,0\nCanon\tEOS M200\t0\t0\t10463,-2173,-1437,-4856,12635,2482,-1216,2915,7237,0,0,0\nCanon\tEOS M2\t0\t0\t6400,-480,-888,-5294,13416,2047,-1296,2203,6137,0,0,0\nCanon\tEOS M100\t0\t0\t8532,-701,-1167,-4095,11879,2508,-797,2424,7010,0,0,0\nCanon\tEOS M10\t0\t0\t6400,-480,-888,-5294,13416,2047,-1296,2203,6137,0,0,0\nCanon\tEOS M\t0\t0\t6602,-841,-939,-4472,12458,2247,-975,2039,6148,0,0,0\nCanon\tEOS-1Ds Mark III\t0\t15280\t5859,-211,-930,-8255,16017,2353,-1732,1887,7448,0,0,0\nCanon\tEOS-1Ds Mark II\t0\t3712\t6517,-602,-867,-8180,15926,2378,-1618,1771,7633,0,0,0\nCanon\tEOS-1D Mark IV\t0\t15280\t6014,-220,-795,-4109,12014,2361,-561,1824,5787,0,0,0\nCanon\tEOS-1D Mark III\t0\t15280\t6291,-540,-976,-8350,16145,2311,-1714,1858,7326,0,0,0\nCanon\tEOS-1D Mark II N\t0\t3712\t6240,-466,-822,-8180,15825,2500,-1801,1938,8042,0,0,0",
        "Canon\tEOS-1D Mark II\t0\t3712\t6264,-582,-724,-8312,15948,2504,-1744,1919,8664,0,0,0\nCanon\tEOS-1Ds\t0\t3616\t3925,4060,-1739,-8973,16552,2545,-3287,3945,8243,0,0,0\nCanon\tEOS-1D C\t0\t15438\t6847,-614,-1014,-4669,12737,2139,-1197,2488,6846,0,0,0\nCanon\tEOS-1D X Mark III\t0\t0\t8971,-2022,-1242,-5405,13249,2380,-1280,2483,6072,0,0,0\nCanon\tEOS-1D X Mark II\t0\t15438\t7596,-978,-967,-4808,12571,2503,-1398,2567,5752,0,0,0\nCanon\tEOS-1D X\t0\t15438\t6847,-614,-1014,-4669,12737,2139,-1197,2488,6846,0,0,0\nCanon\tEOS-1D\t0\t3616\t6806,-179,-1020,-8097,16415,1687,-3267,4236,7690,0,0,0\nCanon\tEOS C500\t853\t0\t17851,-10604,922,-7425,16662,763,-3660,3636,22278,0,0,0\nCanon\tIXUS 160\t0\t0\t11657,-3781,-1136,-3544,11262,2283,-160,1219,4700,0,0,0\nCanon\tPowerShot 600\t0\t0\t-3822,10019,1311,4085,-157,3386,-5341,10829,4812,-1969,10969,1126\nCanon\tPowerShot A3300 IS\t0\t0\t10826,-3654,-1023,-3215,11310,1906,0,999,4960,0,0,0\nCanon\tPowerShot A460\t0\t0\t6493,-2338,-885,-1589,5934,697,-445,1368,2543,0,0,0\nCanon\tPowerShot A470\t0\t0\t12513,-4407,-1242,-2680,10276,2405,-878,2215,4734,0,0,0\nCanon\tPowerShot A530\t0\t0\t7252,-2405,-1223,-2102,6560,523,-112,704,3007,0,0,0\nCanon\tPowerShot A50\t0\t0\t-6233,10706,1825,3260,821,3980,-6512,10745,6287,-2539,12232,262\nCanon\tPowerShot A5\t0\t0\t-5707,10308,2002,2662,1829,4139,-6265,11063,6033,-2659,11911,593\nCanon\tPowerShot A610\t0\t0\t15591,-6402,-1592,-5365,13198,2168,-1300,1824,5075,0,0,0\nCanon\tPowerShot A620\t0\t0\t15265,-6193,-1558,-4125,12116,2010,-888,1639,5220,0,0,0\nCanon\tPowerShot A630\t0\t0\t14201,-5308,-1757,-6087,14472,1617,-2191,3105,5348,0,0,0\nCanon\tPowerShot A640\t0\t0\t13124,-5329,-1390,-3602,11658,1944,-1612,2863,4885,0,0,0\nCanon\tPowerShot A650 IS\t0\t0\t9427,-3036,-959,-2581,10671,1911,-1039,1982,4430,0,0,0\nCanon\tPowerShot A720 IS\t0\t0\t14573,-5482,-1546,-1266,9799,1468,-1040,1912,3810,0,0,0\nCanon\tPowerShot D10\t127\t0\t14052,-5229,-1156,-1325,9420,2252,-498,1957,4116,0,0,0\nCanon\tPowerShot G10\t0\t0\t11093,-3906,-1028,-5047,12492,2879,-1003,1750,5561,0,0,0\nCanon\tPowerShot G11\t0\t0\t12177,-4817,-1069,-1612,9864,2049,-98,850,4471,0,0,0\nCanon\tPowerShot G12\t0\t0\t13244,-5501,-1248,-1508,9858,1935,-270,1083,4366,0,0,0\nCanon\tPowerShot G15\t0\t0\t7474,-2301,-567,-4056,11456,2975,-222,716,4181,0,0,0\nCanon\tPowerShot G16\t0\t0\t8020,-2687,-682,-3704,11879,2052,-965,1921,5556,0,0,0\nCanon\tPowerShot G1 X Mark III\t0\t0\t8532,-701,-1167,-4095,11879,2508,-797,2424,7010,0,0,0\nCanon\tPowerShot G1 X Mark II\t0\t0\t7378,-1255,-1043,-4088,12251,2048,-876,1946,5805,0,0,0\nCanon\tPowerShot G1 X\t0\t0\t7378,-1255,-1043,-4088,12251,2048,-876,1946,5805,0,0,0\nCanon\tPowerShot G1\t0\t0\t-5686,10300,2223,4725,-1157,4383,-6128,10783,6163,-2688,12093,604\nCanon\tPowerShot G2\t0\t0\t9194,-2787,-1059,-8098,15657,2608,-2610,3064,7867,0,0,0\nCanon\tPowerShot G3 X\t0\t0\t9701,-3857,-921,-3149,11537,1817,-786,1817,5147,0,0,0\nCanon\tPowerShot G3\t0\t0\t9326,-2882,-1084,-7940,15447,2677,-2620,3090,7740,0,0,0\nCanon\tPowerShot G5 X Mark II\t0\t0\t11629,-5713,-914,-2706,11090,1842,-206,1225,5515,0,0,0\nCanon\tPowerShot G5 X\t0\t0\t9602,-3823,-937,-2984,11495,1675,-407,1415,5049,0,0,0\nCanon\tPowerShot G5\t0\t0\t9869,-2972,-942,-7314,15098,2369,-1898,2536,7282,0,0,0\nCanon\tPowerShot G6\t0\t0\t9876,-3774,-871,-7613,14807,3071,-1448,1305,7485,0,0,0\nCanon\tPowerShot G7 X Mark III\t0\t0\t11629,-5713,-914,-2706,11090,1842,-206,1225,5515,0,0,0",
        "Canon\tPowerShot G7 X Mark II\t0\t0\t9602,-3823,-937,-2984,11495,1675,-407,1415,5049,0,0,0\nCanon\tPowerShot G7 X\t0\t0\t9602,-3823,-937,-2984,11495,1675,-407,1415,5049,0,0,0\nCanon\tPowerShot G9 X Mark II\t0\t0\t10056,-4131,-944,-2576,11143,1625,-238,1294,5179,0,0,0\nCanon\tPowerShot G9 X\t0\t0\t9602,-3823,-937,-2984,11495,1675,-407,1415,5049,0,0,0\nCanon\tPowerShot G9\t0\t0\t7368,-2141,-598,-5621,13254,2625,-1418,1696,5743,0,0,0\nCanon\tPowerShot S2 IS\t0\t0\t5477,-1435,-992,-1868,6639,510,-58,792,2670,0,0,0\nCanon\tPowerShot S3 IS\t0\t0\t14062,-5199,-1446,-4712,12470,2243,-1286,2028,4836,0,0,0\nCanon\tPowerShot S30\t0\t0\t10744,-3813,-1142,-7962,15966,2075,-2492,2805,7744,0,0,0\nCanon\tPowerShot S40\t0\t0\t8606,-2573,-949,-8237,15489,2974,-2649,3076,9100,0,0,0\nCanon\tPowerShot S45\t0\t0\t8251,-2410,-964,-8047,15430,2823,-2380,2824,8119,0,0,0\nCanon\tPowerShot S50\t0\t0\t8979,-2658,-871,-7721,15500,2357,-1773,2366,6634,0,0,0\nCanon\tPowerShot S60\t0\t0\t8794,-2482,-797,-7804,15403,2572,-1422,1996,7083,0,0,0\nCanon\tPowerShot S70\t0\t0\t9976,-3810,-832,-7115,14463,2906,-901,989,7889,0,0,0\nCanon\tPowerShot S90\t0\t0\t12374,-5016,-1049,-1677,9902,2078,-83,852,4683,0,0,0\nCanon\tPowerShot S95\t0\t0\t13440,-5896,-1279,-1236,9598,1931,-180,1001,4651,0,0,0\nCanon\tPowerShot S120\t0\t0\t6961,-1685,-695,-4625,12945,1836,-1114,2152,5518,0,0,0\nCanon\tPowerShot S110\t0\t0\t8039,-2643,-654,-3783,11230,2930,-206,690,4194,0,0,0\nCanon\tPowerShot S100\t0\t0\t7968,-2565,-636,-2873,10697,2513,180,667,4211,0,0,0\nCanon\tPowerShot SD300\t0\t0\t6526,-1720,-1075,-1390,5945,602,-90,820,2380,0,0,0\nCanon\tPowerShot SX1 IS\t0\t0\t6578,-259,-502,-5974,13030,3309,-308,1058,4970,0,0,0\nCanon\tPowerShot SX20 IS\t0\t0\t8275,-2904,-1260,-128,5305,505,51,481,2450,0,0,0\nCanon\tPowerShot SX30 IS\t0\t0\t13014,-4698,-1026,-2001,9615,2386,-164,1423,3759,0,0,0\nCanon\tPowerShot SX40 HS\t0\t0\t54480,-17404,-8039,-7505,44044,1136,-580,7158,11891,0,0,0\nCanon\tPowerShot SX50 HS\t0\t0\t12432,-4753,-1247,-2110,10691,1629,-412,1623,4926,0,0,0\nCanon\tPowerShot SX60 HS\t0\t0\t13161,-5451,-1344,-1989,10654,1531,-47,1271,4955,0,0,0\nCanon\tPowerShot SX70 HS\t0\t0\t18285,-8907,-1951,-1845,10688,1323,364,1101,5139,0,0,0\nCanon\tPowerShot SX110 IS\t0\t0\t14134,-5576,-1527,-1991,10719,1273,-1158,1929,3581,0,0,0\nCanon\tPowerShot SX120 IS\t0\t0\t7286,-2242,-1047,41,4401,457,269,684,1864,0,0,0\nCanon\tPowerShot SX220 HS\t0\t0\t13898,-5076,-1447,-1405,10109,1297,-244,1860,3687,0,0,0\nCanon\tPowerShot SX710 HS\t0\t0\t13161,-5451,-1344,-1989,10654,1531,-47,1271,4955,0,0,0\nCanon\tPowerShot Pro1\t0\t0\t10062,-3522,-1000,-7643,15117,2730,-765,817,7322,0,0,0\nCanon\tPowerShot Pro70\t34\t0\t-5106,10695,1576,3820,53,4566,-6497,10736,6701,-3336,11887,1394\nCanon\tPowerShot Pro90\t0\t0\t-5912,10768,2288,4612,-989,4333,-6153,10897,5944,-2907,12288,624\nCasio\tEX-F1\t0\t0\t9084,-2016,-848,-6711,14351,2570,-1059,1725,6135,0,0,0\nCasio\tEX-FH100\t0\t0\t12771,-4179,-1558,-2149,10938,1375,-453,1751,4494,0,0,0\nCasio\tEX-S20\t0\t0\t11634,-3924,-1128,-4968,12954,2015,-1588,2648,7206,0,0,0\nCasio\tEX-Z750\t0\t0\t10819,-3873,-1099,-4903,13730,1175,-1755,3751,4632,0,0,0\nCasio\tEX-Z10\t128\t4095\t9790,-3338,-603,-2321,10222,2099,-344,1273,4799,0,0,0\nCINE\t650\t0\t0\t3390,480,-500,-800,3610,340,-550,2336,1192,0,0,0\nCINE\t660\t0\t0\t3390,480,-500,-800,3610,340,-550,2336,1192,0,0,0",
        "CINE\t\t0\t0\t20183,-4295,-423,-3940,15330,3985,-280,4870,9800,0,0,0\nContax\tN Digital\t0\t3870\t7777,1285,-1053,-9280,16543,2916,-3677,5679,7060,0,0,0\nDxO\tONE\t0\t0\t6596,-2079,-562,-4782,13016,1933,-970,1581,5181,0,0,0\nEpson\tR-D1\t0\t0\t6827,-1878,-732,-8429,16012,2564,-704,592,7145,0,0,0\nFujifilm\tDBP for GX680\t-128\t4095\t12741,-4916,-1420,-8510,16791,1715,-1767,2302,7771,0,0,0\nFujifilm\tE550\t0\t0\t11044,-3888,-1120,-7248,15167,2208,-1531,2276,8069,0,0,0\nFujifilm\tE900\t0\t0\t9183,-2526,-1078,-7461,15071,2574,-2022,2440,8639,0,0,0\nFujifilm\tF5\t0\t0\t13690,-5358,-1474,-3369,11600,1998,-132,1554,4395,0,0,0\nFujifilm\tF6\t0\t0\t13690,-5358,-1474,-3369,11600,1998,-132,1554,4395,0,0,0\nFujifilm\tF77\t0\t4073\t13690,-5358,-1474,-3369,11600,1998,-132,1554,4395,0,0,0\nFujifilm\tF7\t0\t0\t10004,-3219,-1201,-7036,15047,2107,-1863,2565,7736,0,0,0\nFujifilm\tF810\t0\t0\t11044,-3888,-1120,-7248,15167,2208,-1531,2276,8069,0,0,0\nFujifilm\tF8\t0\t0\t13690,-5358,-1474,-3369,11600,1998,-132,1554,4395,0,0,0\nFujifilm\tF900EXR\t0\t0\t12085,-4727,-953,-3257,11489,2002,-511,2046,4592,0,0,0\nFujifilm\tGFX 100S II\t0\t0\t12806,-5779,-1110,-3546,11507,2318,-177,996,5715,0,0,0\nFujifilm\tGFX 100 II\t0\t0\t12806,-5779,-1110,-3546,11507,2318,-177,996,5715,0,0,0\nFujifilm\tGFX 100\t0\t0\t16212,-8423,-1583,-4336,12583,1937,-195,726,6199,0,0,0\nFujifilm\tGFX 50\t0\t0\t11756,-4754,-874,-3056,11045,2305,-381,1457,6006,0,0,0\nFujifilm\tHS10\t0\t3944\t12440,-3954,-1183,-1123,9674,1708,-83,1614,4086,0,0,0\nFujifilm\tHS2\t0\t0\t13690,-5358,-1474,-3369,11600,1998,-132,1554,4395,0,0,0\nFujifilm\tHS3\t0\t0\t13690,-5358,-1474,-3369,11600,1998,-132,1554,4395,0,0,0\nFujifilm\tHS50EXR\t0\t0\t12085,-4727,-953,-3257,11489,2002,-511,2046,4592,0,0,0\nFujifilm\tIS-1\t0\t0\t21461,-10807,-1441,-2332,10599,1999,289,875,7703,0,0,0\nFujifilm\tIS Pro\t0\t0\t12300,-5110,-1304,-9117,17143,1998,-1947,2448,8100,0,0,0\nFujifilm\tS5000\t0\t0\t8754,-2732,-1019,-7204,15069,2276,-1702,2334,6982,0,0,0\nFujifilm\tS5100\t0\t0\t11940,-4431,-1255,-6766,14428,2542,-993,1165,7421,0,0,0\nFujifilm\tS5200\t0\t0\t9636,-2804,-988,-7442,15040,2589,-1803,2311,8621,0,0,0\nFujifilm\tS6\t0\t0\t12628,-4887,-1401,-6861,14996,1962,-2198,2782,7091,0,0,0\nFujifilm\tS7000\t0\t0\t10190,-3506,-1312,-7153,15051,2238,-2003,2399,7505,0,0,0\nFujifilm\tS9000\t0\t0\t10491,-3423,-1145,-7385,15027,2538,-1809,2275,8692,0,0,0\nFujifilm\tS9100\t0\t0\t12343,-4515,-1285,-7165,14899,2435,-1895,2496,8800,0,0,0\nFujifilm\tS100FS\t-514\t0\t11521,-4355,-1065,-6524,13767,3058,-1466,1984,6045,0,0,0\nFujifilm\tS20Pro\t0\t0\t10004,-3219,-1201,-7036,15047,2107,-1863,2565,7736,0,0,0\nFujifilm\tS20\t-512\t16383\t11401,-4498,-1312,-5088,12751,2613,-838,1568,5941,0,0,0\nFujifilm\tSL1000\t0\t0\t11705,-4262,-1107,-2282,10791,1709,-555,1713,4945,0,0,0\nFujifilm\tS1\t0\t0\t12297,-4882,-1202,-2106,10691,1623,-88,1312,4790,0,0,0\nFujifilm\tS2Pro\t-128\t0\t12741,-4916,-1420,-8510,16791,1715,-1767,2302,7771,0,0,0\nFujifilm\tS3Pro\t0\t0\t11807,-4612,-1294,-8927,16968,1988,-2120,2741,8006,0,0,0\nFujifilm\tS5Pro\t0\t0\t12300,-5110,-1304,-9117,17143,1998,-1947,2448,8100,0,0,0\nFujifilm\tX100F\t0\t0\t11434,-4948,-1210,-3746,12042,1903,-666,1479,5235,0,0,0",
        "Fujifilm\tX100S\t0\t0\t10592,-4262,-1008,-3514,11355,2465,-870,2025,6386,0,0,0\nFujifilm\tX100T\t0\t0\t10592,-4262,-1008,-3514,11355,2465,-870,2025,6386,0,0,0\nFujifilm\tX100VI\t0\t0\t11809,-5358,-1141,-4248,12164,2343,-514,1097,5848,0,0,0\nFujifilm\tX100V\t0\t0\t13717,-6490,-1154,-4348,12266,2335,-690,1286,6134,0,0,0\nFujifilm\tX100\t0\t0\t12161,-4457,-1069,-5034,12874,2400,-795,1724,6904,0,0,0\nFujifilm\tX10\t0\t0\t13509,-6199,-1254,-4430,12733,1865,-331,1441,5022,0,0,0\nFujifilm\tX20\t0\t0\t11768,-4971,-1133,-4904,12927,2183,-480,1723,4605,0,0,0\nFujifilm\tX30\t0\t0\t12328,-5256,-1144,-4469,12927,1675,-87,1291,4351,0,0,0\nFujifilm\tX70\t0\t0\t10450,-4329,-878,-3217,11105,2421,-752,1758,6519,0,0,0\nFujifilm\tXF10\t0\t0\t11673,-4760,-1041,-3988,12058,2166,-771,1417,5569,0,0,0\nFujifilm\tXF1\t0\t0\t13509,-6199,-1254,-4430,12733,1865,-331,1441,5022,0,0,0\nFujifilm\tXQ\t0\t0\t9252,-2704,-1064,-5893,14265,1717,-1101,2341,4349,0,0,0\nFujifilm\tX-Pro1\t0\t0\t10413,-3996,-993,-3721,11640,2361,-733,1540,6011,0,0,0\nFujifilm\tX-Pro2\t0\t0\t11434,-4948,-1210,-3746,12042,1903,-666,1479,5235,0,0,0\nFujifilm\tX-Pro3\t0\t0\t13426,-6334,-1177,-4244,12136,2371,-580,1303,5980,0,0,0\nFujifilm\tX-A10\t0\t0\t11540,-4999,-991,-2949,10963,2278,-382,1049,5605,0,0,0\nFujifilm\tX-A20\t0\t0\t11540,-4999,-991,-2949,10963,2278,-382,1049,5605,0,0,0\nFujifilm\tX-A1\t0\t0\t11086,-4555,-839,-3512,11310,2517,-815,1341,5940,0,0,0\nFujifilm\tX-A2\t0\t0\t10763,-4560,-917,-3346,11311,2322,-475,1135,5843,0,0,0\nFujifilm\tX-A3\t0\t0\t12407,-5222,-1086,-2971,11116,2120,-294,1029,5284,0,0,0\nFujifilm\tX-A5\t0\t0\t11673,-4760,-1041,-3988,12058,2166,-771,1417,5569,0,0,0\nFujifilm\tX-A7\t0\t0\t15055,-7391,-1274,-4062,12071,2238,-610,1217,6147,0,0,0\nFujifilm\tX-E1\t0\t0\t10413,-3996,-993,-3721,11640,2361,-733,1540,6011,0,0,0\nFujifilm\tX-E2S\t0\t0\t11562,-5118,-961,-3022,11007,2311,-525,1569,6097,0,0,0\nFujifilm\tX-E2\t0\t0\t8458,-2451,-855,-4597,12447,2407,-1475,2482,6526,0,0,0\nFujifilm\tX-E3\t0\t0\t11434,-4948,-1210,-3746,12042,1903,-666,1479,5235,0,0,0\nFujifilm\tX-E4\t0\t0\t13426,-6334,-1177,-4244,12136,2371,-580,1303,5980,0,0,0\nFujifilm\tX-H1\t0\t0\t11434,-4948,-1210,-3746,12042,1903,-666,1479,5235,0,0,0\nFujifilm\tX-H2S\t0\t0\t12836,-5909,-1032,-3087,11132,2236,-35,872,5330,0,0,0\nFujifilm\tX-H2\t0\t0\t11809,-5358,-1141,-4248,12164,2343,-514,1097,5848,0,0,0\nFujifilm\tX-M1\t0\t0\t10413,-3996,-993,-3721,11640,2361,-733,1540,6011,0,0,0\nFujifilm\tX-M5\t0\t0\t12836,-5909,-1032,-3087,11132,2236,-35,872,5330,0,0,0\nFujifilm\tX-S20\t0\t0\t12836,-5909,-1032,-3087,11132,2236,-35,872,5330,0,0,0\nFujifilm\tX-S10\t0\t0\t13426,-6334,-1177,-4244,12136,2371,-580,1303,5980,0,0,0\nFujifilm\tX-S1\t0\t0\t13509,-6199,-1254,-4430,12733,1865,-331,1441,5022,0,0,0\nFujifilm\tX-T100\t0\t0\t11673,-4760,-1041,-3988,12058,2166,-771,1417,5569,0,0,0\nFujifilm\tX-T1\t0\t0\t8458,-2451,-855,-4597,12447,2407,-1475,2482,6526,0,0,0\nFujifilm\tX-T200\t0\t0\t15055,-7391,-1274,-4062,12071,2238,-610,1217,6147,0,0,0\nFujifilm\tX-T2\t0\t0\t11434,-4948,-1210,-3746,12042,1903,-666,1479,5235,0,0,0\nFujifilm\tX-T3\t0\t0\t13426,-6334,-1177,-4244,12136,2371,-580,1303,5980,0,0,0",
        "Fujifilm\tX-T4\t0\t0\t13426,-6334,-1177,-4244,12136,2371,-580,1303,5980,0,0,0\nFujifilm\tX-T5\t0\t0\t11809,-5358,-1141,-4248,12164,2343,-514,1097,5848,0,0,0\nGITUP\tG3DUO\t130\t62000\t8489,-2583,-1036,-8051,15583,2643,-1307,1407,7354,0,0,0\nGITUP\tGIT2P\t4160\t0\t8489,-2583,-1036,-8051,15583,2643,-1307,1407,7354,0,0,0\nGITUP\tGIT2\t3200\t0\t8489,-2583,-1036,-8051,15583,2643,-1307,1407,7354,0,0,0\nGoPro\tHERO5 Black\t0\t0\t10344,-4210,-620,-2315,10625,1948,93,1058,5541,0,0,0\nHasselblad\tL1D-20c\t0\t0\t7310,-2746,-646,-2991,10847,2469,163,585,6324,0,0,0\nHasselblad\t16-Uncoated-3FR\t0\t0\t8519,-3260,-280,-5081,13459,1738,-1449,2960,7809,0,0,0\nHasselblad\t16-Uncoated-FFF\t0\t0\t8068,-2959,-108,-5788,13608,2389,-1002,2237,8162,0,0,0\nHasselblad\t16-Uncoated\t0\t0\t8519,-3260,-280,-5081,13459,1738,-1449,2960,7809,0,0,0\nHasselblad\t22-Uncoated-3FR\t0\t0\t8523,-3257,-280,-5078,13458,1743,-1449,2961,7809,0,0,0\nHasselblad\t22-Uncoated-FFF\t0\t0\t8068,-2959,-108,-5788,13608,2389,-1002,2237,8162,0,0,0\nHasselblad\t22-Uncoated\t0\t0\t8519,-3260,-280,-5081,13459,1738,-1449,2960,7809,0,0,0\nHasselblad\t31-Uncoated-FFF\t0\t0\t5155,-1201,200,-5841,13197,2950,-1101,2317,6988,0,0,0\nHasselblad\t31-Uncoated\t0\t0\t5458,-1448,145,-4479,12338,2401,-1659,3086,6710,0,0,0\nHasselblad\t39-Uncoated-3FR\t0\t0\t3904,-100,262,-4318,12407,2128,-1598,3594,6233,0,0,0\nHasselblad\t39-Uncoated-FFF\t0\t0\t4739,-932,295,-4829,12220,2952,-1027,2341,7083,0,0,0\nHasselblad\t39-Uncoated\t0\t0\t3894,-110,287,-4672,12610,2295,-2092,4100,6196,0,0,0\nHasselblad\t39-Coated-3FR\t0\t0\t5427,-1147,173,-3834,12073,1969,-1444,3320,5621,0,0,0\nHasselblad\t39-Coated-FFF\t0\t0\t5323,-1233,399,-4926,12362,2894,-856,2471,5961,0,0,0\nHasselblad\t39-Coated\t0\t0\t3857,452,-46,-6008,14477,1596,-2627,4481,5718,0,0,0\nHasselblad\t40-Coated5-3FR\t0\t0\t7014,-2067,-540,-4821,13016,1980,-1663,3089,6940,0,0,0\nHasselblad\t40-Coated5-FFF\t0\t0\t5963,-1357,-172,-5439,12762,3007,-964,2222,7172,0,0,0\nHasselblad\t40-Coated5\t0\t0\t6159,-1402,-177,-5439,12762,3007,-955,2200,7104,0,0,0\nHasselblad\t40-Coated-3FR\t0\t0\t6550,-1681,-399,-4626,12598,2257,-1807,3354,6486,0,0,0\nHasselblad\t40-Coated-FFF\t0\t0\t6041,-1375,-174,-5439,10000,3007,-930,2145,6923,0,0,0\nHasselblad\t40-Coated\t0\t0\t6159,-1402,-177,-5439,12762,3007,-955,2200,7104,0,0,0\nHasselblad\t50-Coated5-3FR\t0\t0\t5707,-693,-382,-4285,12669,1773,-1615,3519,5410,0,0,0\nHasselblad\t50-Coated5-FFF\t0\t0\t5263,-612,39,-4950,12426,2843,-935,2423,5941,0,0,0\nHasselblad\t50-Coated5\t0\t0\t5656,-659,-346,-3923,12306,1791,-1602,3509,5442,0,0,0\nHasselblad\t50-Coated-3FR\t0\t0\t5656,-659,-346,-3923,12305,1790,-1602,3509,5442,0,0,0\nHasselblad\t50-Coated-FFF\t0\t0\t5280,-614,39,-4950,12426,2843,-939,2434,5968,0,0,0\nHasselblad\t50-Coated\t0\t0\t5656,-659,-346,-3923,12306,1791,-1602,3509,5442,0,0,0\nHasselblad\t50-15-Coated5-II-3FR\t0\t0\t10887,-6152,1034,-3564,12412,4224,63,626,10123,0,0,0\nHasselblad\t50-15-Coated5-II-FFF\t0\t0\t4932,-835,141,-4878,11868,3437,-1138,1961,7067,0,0,0\nHasselblad\t50-15-Coated5-II\t0\t0\t8737,-4937,830,-2860,9961,3390,51,502,8124,0,0,0\nHasselblad\t50-15-Coated5\t0\t0\t4932,-835,141,-4878,11868,3437,-1138,1961,7067,0,0,0\nHasselblad\t60-Coated-3FR\t0\t0\t9296,336,-1088,-6442,14323,2289,-1433,2942,5756,0,0,0\nHasselblad\t60-Coated\t0\t0\t9662,-684,-279,-4903,12293,2950,-344,1669,6024,0,0,0\nHasselblad\t100-17-Coated5\t0\t0\t5110,-1357,-308,-5573,12835,3077,-1279,2025,7010,0,0,0",
        "Hasselblad\t100-20-Coated6\t0\t0\t6468,-1899,-545,-4526,12267,2542,-388,1276,6096,0,0,0\nHTC\tOne A9\t64\t1023\t101,-20,-2,-11,145,41,-24,1,56,0,0,0\nImacon\tIxpress\t0\t0\t7025,-1415,-704,-5188,13765,1424,-1248,2742,6038,0,0,0\nKodak\tNC2000\t0\t0\t13891,-6055,-803,-465,9919,642,2121,82,1291,0,0,0\nKodak\tDCS315C\t-8\t0\t17523,-4827,-2510,756,8546,-137,6113,1649,2250,0,0,0\nKodak\tDCS330C\t-8\t0\t20620,-7572,-2801,-103,10073,-396,3551,-233,2220,0,0,0\nKodak\tDCS420\t0\t0\t10868,-1852,-644,-1537,11083,484,2343,628,2216,0,0,0\nKodak\tDCS46\t0\t0\t10592,-2206,-967,-1944,11685,230,2206,670,1273,0,0,0\nKodak\tDCS520C\t-178\t0\t24542,-10860,-3401,-1490,11370,-297,2858,-605,3225,0,0,0\nKodak\tDCS560C\t-177\t0\t20482,-7172,-3125,-1033,10410,-285,2542,226,3136,0,0,0\nKodak\tDCS620C\t-177\t0\t23617,-10175,-3149,-2054,11749,-272,2586,-489,3453,0,0,0\nKodak\tDCS620X\t-176\t0\t13095,-6231,154,12221,-21,-2137,895,4602,2258,0,0,0\nKodak\tDCS660C\t-173\t0\t18244,-6351,-2739,-791,11193,-521,3711,-129,2802,0,0,0\nKodak\tDCS720X\t0\t0\t11775,-5884,950,9556,1846,-1286,-1019,6221,2728,0,0,0\nKodak\tDCS760C\t0\t0\t16623,-6309,-1411,-4344,13923,323,2285,274,2926,0,0,0\nKodak\tDCS Pro SLR\t0\t0\t5494,2393,-232,-6427,13850,2846,-1876,3997,5445,0,0,0\nKodak\tDCS Pro 14nx\t0\t0\t5494,2393,-232,-6427,13850,2846,-1876,3997,5445,0,0,0\nKodak\tDCS Pro 14\t0\t0\t7791,3128,-776,-8588,16458,2039,-2455,4006,6198,0,0,0\nKodak\tEOSDCS1\t0\t0\t10592,-2206,-967,-1944,11685,230,2206,670,1273,0,0,0\nKodak\tEOSDCS3\t0\t0\t9898,-2700,-940,-2478,12219,206,1985,634,1031,0,0,0\nKodak\tProBack645\t0\t0\t16414,-6060,-1470,-3555,13037,473,2545,122,4948,0,0,0\nKodak\tProBack\t0\t0\t21179,-8316,-2918,-915,11019,-165,3477,-180,4210,0,0,0\nKodak\tPIXPRO AZ901\t0\t0\t21875,-8006,-2558,634,8194,1104,1535,951,6969,0,0,0\nKodak\tP712\t0\t3963\t9658,-3314,-823,-5163,12695,2768,-1342,1843,6044,0,0,0\nKodak\tP850\t0\t3964\t10511,-3836,-1102,-6946,14587,2558,-1481,1792,6246,0,0,0\nKodak\tP880\t0\t3963\t12805,-4662,-1376,-7480,15267,2360,-1626,2194,7904,0,0,0\nKodak\tZ980\t0\t0\t11313,-3559,-1101,-3893,11891,2257,-1214,2398,4908,0,0,0\nKodak\tZ981\t0\t0\t12729,-4717,-1188,-1367,9187,2582,274,860,4411,0,0,0\nKodak\tZ990\t0\t4077\t11749,-4048,-1309,-1867,10572,1489,-138,1449,4522,0,0,0\nKodak\tZ1015\t0\t3825\t11265,-4286,-992,-4694,12343,2647,-1090,1523,5447,0,0,0\nLeaf\tAFi 54S\t0\t0\t8236,1746,-1313,-8251,15953,2428,-3672,5786,5771,0,0,0\nLeaf\tAFi 65S\t0\t0\t7914,1414,-1190,-8776,16582,2280,-2811,4605,5562,0,0,0\nLeaf\tAFi 75S\t0\t0\t7914,1414,-1190,-8776,16582,2280,-2811,4605,5562,0,0,0\nLeaf\tAptus 17\t0\t0\t8236,1746,-1313,-8251,15953,2428,-3672,5786,5771,0,0,0\nLeaf\tAptus 22\t0\t0\t8236,1746,-1313,-8251,15953,2428,-3672,5786,5771,0,0,0\nLeaf\tAptus 54S\t0\t0\t8236,1746,-1313,-8251,15953,2428,-3672,5786,5771,0,0,0\nLeaf\tAptus 65S\t0\t0\t7914,1414,-1190,-8776,16582,2280,-2811,4605,5562,0,0,0\nLeaf\tAptus 65\t0\t0\t7914,1414,-1190,-8776,16582,2280,-2811,4605,5562,0,0,0\nLeaf\tAptus 75S\t0\t0\t7914,1414,-1190,-8776,16582,2280,-2811,4605,5562,0,0,0\nLeaf\tAptus 75\t0\t0\t7914,1414,-1190,-8776,16582,2280,-2811,4605,5562,0,0,0",
        "Leaf\tC-Most\t0\t0\t3952,2188,449,-6701,14584,2275,-4536,7349,6535,0,0,0\nLeaf\tCredo 40\t0\t0\t8035,435,-962,-6001,13872,2320,-1159,3065,5434,0,0,0\nLeaf\tCredo 50\t0\t0\t10325,845,-604,-4113,13385,481,-1791,4163,6924,0,0,0\nLeaf\tCredo 60\t0\t0\t8035,435,-962,-6001,13872,2320,-1159,3065,5434,0,0,0\nLeaf\tCredo 80\t0\t0\t6294,686,-712,-5435,13417,2211,-1006,2435,5042,0,0,0\nLeaf\tValeo 11\t0\t0\t8236,1746,-1313,-8251,15953,2428,-3672,5786,5771,0,0,0\nLeaf\tValeo 17\t0\t0\t8236,1746,-1313,-8251,15953,2428,-3672,5786,5771,0,0,0\nLeaf\tValeo 22\t0\t0\t8236,1746,-1313,-8251,15953,2428,-3672,5786,5771,0,0,0\nLeaf\tValeo 6\t0\t0\t3952,2188,449,-6701,14584,2275,-4536,7349,6535,0,0,0\nLeaf\tAFi-II 7\t0\t0\t7691,-108,-339,-6185,13627,2833,-2046,3899,5952,0,0,0\nLeaf\tAFi-II 10\t0\t0\t6719,1147,-148,-6929,14061,3176,-1781,3343,5424,0,0,0\nLeaf\tAptus-II 5\t0\t0\t7914,1414,-1190,-8777,16582,2280,-2811,4605,5562,0,0,0\nLeaf\tAptus-II 6\t0\t0\t7989,-113,-352,-6185,13627,2833,-2028,3866,5901,0,0,0\nLeaf\tAptus-II 7\t0\t0\t8209,-116,-362,-6185,13627,2833,-1962,3740,5709,0,0,0\nLeaf\tAptus-II 8\t0\t0\t7361,1257,-163,-6929,14061,3176,-1839,3454,5603,0,0,0\nLeaf\tAptus-II 10R\t0\t0\t7167,1224,-158,-6929,14061,3176,-1826,3429,5562,0,0,0\nLeaf\tAptus-II 10\t0\t0\t7527,1285,-166,-6929,14061,3176,-1995,3747,6077,0,0,0\nLeaf\tAptus-II 12\t0\t0\t7361,1257,-163,-6929,14061,3176,-1695,3182,5162,0,0,0\nLeica\tCL\t0\t0\t7743,-2896,-921,-4211,12271,2169,-697,1562,5491,0,0,0\nLeica\tM8\t0\t0\t7675,-2196,-305,-5860,14119,1856,-2425,4006,6578,0,0,0\nLeica\tM9\t0\t0\t6687,-1751,-291,-3556,11373,2492,-548,2204,7146,0,0,0\nLeica\tM10\t0\t0\t9090,-3342,-740,-4006,13456,493,-569,2266,6871,0,0,0\nLeica\tM (Typ 2\t0\t0\t7199,-2140,-712,-4005,13327,649,-810,2521,6673,0,0,0\nLeica\tQ (Typ 116)\t0\t0\t10068,-4043,-1068,-5319,14268,1044,-765,1701,6522,0,0,0\nLeica\tQ2\t0\t0\t12312,-5440,-1307,-6408,15499,824,-1075,1677,7220,0,0,0\nLeica\tSL (Typ 601)\t0\t0\t11865,-4523,-1441,-5423,14458,935,-1587,2687,4830,0,0,0\nLeica\tS (Typ 007)\t0\t0\t6063,-2234,-231,-5210,13787,1500,-1043,2866,6997,0,0,0\nLeica\tS2\t0\t0\t5627,-721,-447,-4423,12456,2192,-1048,2948,7379,0,0,0\nLeica\tS3\t0\t0\t5092,-1630,-470,-6313,14297,2170,-1603,3135,5982,0,0,0\nLeica\tS\t0\t0\t5749,-1072,-382,-4274,12432,2048,-1166,3104,7105,0,0,0\nLeica\tTL2\t0\t0\t6375,-2062,-732,-4878,12838,2262,-877,1705,6204,0,0,0\nLeica\tT\t0\t0\t6295,-1679,-475,-5586,13046,2837,-1410,1889,7075,0,0,0\nLeica\tX2\t0\t0\t8336,-2853,-699,-4425,11989,2760,-954,1625,6396,0,0,0\nLeica\tX1\t0\t0\t9055,-2611,-666,-4906,12652,2519,-555,1384,7417,0,0,0\nLeica\tX\t0\t0\t9062,-3198,-828,-4065,11772,2603,-761,1468,6458,0,0,0\nMamiya\tZD\t0\t0\t7645,2579,-1363,-8689,16717,2015,-3712,5941,5961,0,0,0\nMicron\t2010\t110\t0\t16695,-3761,-2151,155,9682,163,3433,951,4904,0,0,0\nMinolta\tDiMAGE 5\t0\t3965\t9117,-3063,-973,-7949,15763,2306,-2752,3136,8093,0,0,0\nMinolta\tDiMAGE 7Hi\t0\t3965\t11555,-4064,-1256,-7903,15633,2409,-2811,3320,7358,0,0,0\nMinolta\tDiMAGE 7i\t0\t3965\t11050,-3791,-1199,-7875,15585,2434,-2797,3359,7560,0,0,0",
        "Minolta\tDiMAGE 7\t0\t3965\t9258,-2879,-1008,-8076,15847,2351,-2806,3280,7821,0,0,0\nMinolta\tDiMAGE A1\t0\t3979\t9274,-2548,-1167,-8220,16324,1943,-2273,2721,8340,0,0,0\nMinolta\tDiMAGE A200\t0\t0\t8560,-2487,-986,-8112,15535,2771,-1209,1324,7743,0,0,0\nMinolta\tDiMAGE A2\t0\t3983\t9097,-2726,-1053,-8073,15506,2762,-966,981,7763,0,0,0\nMinolta\tDiMAGE Z2\t0\t0\t11280,-3564,-1370,-4655,12374,2282,-1423,2168,5396,0,0,0\nMinolta\tDG-5D\t0\t4091\t10284,-3283,-1086,-7957,15762,2316,-829,882,6644,0,0,0\nMinolta\tDG-7D\t0\t4091\t10239,-3104,-1099,-8037,15727,2451,-927,925,6871,0,0,0\nMotorola\tPIXL\t0\t0\t8898,-989,-1033,-3292,11619,1674,-661,3178,5216,0,0,0\nNikon\t1 AW1\t0\t0\t6588,-1305,-693,-3277,10987,2634,-355,2016,5106,0,0,0\nNikon\t1 J3\t0\t0\t6588,-1305,-693,-3277,10987,2634,-355,2016,5106,0,0,0\nNikon\t1 J4\t0\t0\t5958,-1559,-571,-4021,11453,2939,-634,1548,5087,0,0,0\nNikon\t1 J5\t0\t0\t7520,-2518,-645,-3844,12102,1945,-913,2249,6835,0,0,0\nNikon\t1 S2\t-200\t0\t6612,-1342,-618,-3338,11055,2623,-174,1792,5075,0,0,0\nNikon\t1 V2\t0\t0\t6588,-1305,-693,-3277,10987,2634,-355,2016,5106,0,0,0\nNikon\t1 V3\t-200\t0\t5958,-1559,-571,-4021,11453,2939,-634,1548,5087,0,0,0\nNikon\t1 \t0\t0\t8994,-2667,-865,-4594,12324,2552,-699,1786,6260,0,0,0\nNikon\tCOOLPIX 2100\t0\t0\t13142,-4152,-1596,-4655,12374,2282,-1769,2696,6711,0,0,0\nNikon\tCOOLPIX 2500\t0\t0\t-5547,11762,2189,5814,-558,3342,-4924,9840,5949,688,9083,96\nNikon\tCOOLPIX 3200\t0\t0\t9846,-2085,-1019,-3278,11109,2170,-774,2134,5745,0,0,0\nNikon\tCOOLPIX 4300\t0\t0\t11280,-3564,-1370,-4655,12374,2282,-1423,2168,5396,0,0,0\nNikon\tCOOLPIX 4500\t0\t0\t-5547,11762,2189,5814,-558,3342,-4924,9840,5949,688,9083,96\nNikon\tCOOLPIX 5000\t0\t0\t-6678,12805,2248,5725,-499,3375,-5903,10713,6034,-270,9976,134\nNikon\tCOOLPIX 5400\t0\t0\t9349,-2988,-1001,-7918,15766,2266,-2097,2680,6839,0,0,0\nNikon\tCOOLPIX 5700\t0\t0\t-6475,12496,2428,5409,-16,3180,-5965,10912,5866,-177,9918,248\nNikon\tCOOLPIX 8400\t0\t0\t7842,-2320,-992,-8154,15718,2599,-1098,1342,7560,0,0,0\nNikon\tCOOLPIX 8700\t0\t0\t8489,-2583,-1036,-8051,15583,2643,-1307,1407,7354,0,0,0\nNikon\tCOOLPIX 8800\t0\t0\t7971,-2314,-913,-8451,15762,2894,-1442,1520,7610,0,0,0\nNikon\tCOOLPIX 700\t0\t989\t-3746,10611,1665,9621,-1734,2114,-2389,7082,3064,3406,6116,-244\nNikon\tCOOLPIX 800\t0\t989\t-3746,10611,1665,9621,-1734,2114,-2389,7082,3064,3406,6116,-244\nNikon\tCOOLPIX 950\t0\t989\t-3746,10611,1665,9621,-1734,2114,-2389,7082,3064,3406,6116,-244\nNikon\tCOOLPIX 995\t0\t0\t-5547,11762,2189,5814,-558,3342,-4924,9840,5949,688,9083,96\nNikon\tCOOLPIX A1000\t0\t0\t10601,-3487,-1127,-2931,11443,1676,-587,1740,5278,0,0,0\nNikon\tCOOLPIX B700\t0\t0\t14387,-6014,-1299,-1357,9975,1616,467,1047,4744,0,0,0\nNikon\tCOOLPIX A\t0\t0\t8198,-2239,-724,-4871,12389,2798,-1043,2050,7181,0,0,0\nNikon\tCOOLPIX P1000\t0\t0\t14294,-6116,-1333,-1628,10219,1637,-14,1158,5022,0,0,0\nNikon\tCOOLPIX P6000\t0\t0\t9698,-3367,-914,-4706,12584,2368,-837,968,5801,0,0,0\nNikon\tCOOLPIX P7000\t0\t0\t11432,-3679,-1111,-3169,11239,2202,-791,1380,4455,0,0,0\nNikon\tCOOLPIX P7100\t0\t0\t11053,-4269,-1024,-1976,10182,2088,-526,1263,4469,0,0,0\nNikon\tCOOLPIX P7700\t-3200\t0\t10321,-3920,-931,-2750,11146,1824,-442,1545,5539,0,0,0\nNikon\tCOOLPIX P7800\t-3200\t0\t10321,-3920,-931,-2750,11146,1824,-442,1545,5539,0,0,0",
        "Nikon\tCOOLPIX P330\t-200\t0\t10321,-3920,-931,-2750,11146,1824,-442,1545,5539,0,0,0\nNikon\tCOOLPIX P340\t-200\t0\t10321,-3920,-931,-2750,11146,1824,-442,1545,5539,0,0,0\nNikon\tCoolpix P950\t0\t0\t13307,-5641,-1290,-2048,10581,1689,-64,1222,5176,0,0,0\nNikon\tD3000\t0\t0\t8736,-2458,-935,-9075,16894,2251,-1354,1242,8263,0,0,0\nNikon\tD3100\t0\t0\t7911,-2167,-813,-5327,13150,2408,-1288,2483,7968,0,0,0\nNikon\tD3200\t0\t4025\t7013,-1408,-635,-5268,12902,2640,-1470,2801,7379,0,0,0\nNikon\tD3300\t0\t0\t6988,-1384,-714,-5631,13410,2447,-1485,2204,7318,0,0,0\nNikon\tD3400\t0\t0\t6988,-1384,-714,-5631,13410,2447,-1485,2204,7318,0,0,0\nNikon\tD3500\t0\t0\t8821,-2938,-785,-4178,12142,2287,-824,1651,6860,0,0,0\nNikon\tD5000\t0\t3840\t7309,-1403,-519,-8474,16008,2622,-2433,2826,8064,0,0,0\nNikon\tD5100\t0\t15846\t8198,-2239,-724,-4871,12389,2798,-1043,2050,7181,0,0,0\nNikon\tD5200\t0\t0\t8322,-3112,-1047,-6367,14342,2179,-988,1638,6394,0,0,0\nNikon\tD5300\t0\t0\t6988,-1384,-714,-5631,13410,2447,-1485,2204,7318,0,0,0\nNikon\tD5500\t0\t0\t8821,-2938,-785,-4178,12142,2287,-824,1651,6860,0,0,0\nNikon\tD5600\t0\t0\t8821,-2938,-785,-4178,12142,2287,-824,1651,6860,0,0,0\nNikon\tD7000\t0\t0\t8198,-2239,-724,-4871,12389,2798,-1043,2050,7181,0,0,0\nNikon\tD7100\t0\t0\t8322,-3112,-1047,-6367,14342,2179,-988,1638,6394,0,0,0\nNikon\tD7200\t0\t0\t8322,-3112,-1047,-6367,14342,2179,-988,1638,6394,0,0,0\nNikon\tD7500\t0\t0\t8813,-3210,-1036,-4703,12868,2021,-1054,1940,6129,0,0,0\nNikon\tD100\t0\t0\t5902,-933,-782,-8983,16719,2354,-1402,1455,6464,0,0,0\nNikon\tD200\t0\t4028\t8367,-2248,-763,-8758,16447,2422,-1527,1550,8053,0,0,0\nNikon\tD300\t0\t0\t9030,-1992,-715,-8465,16302,2255,-2689,3217,8069,0,0,0\nNikon\tD500\t0\t0\t8813,-3210,-1036,-4703,12868,2021,-1054,1940,6129,0,0,0\nNikon\tD600\t0\t15879\t8178,-2245,-609,-4857,12394,2776,-1207,2086,7298,0,0,0\nNikon\tD610\t0\t0\t8178,-2245,-609,-4857,12394,2776,-1207,2086,7298,0,0,0\nNikon\tD700\t0\t0\t8139,-2171,-663,-8747,16541,2295,-1925,2008,8093,0,0,0\nNikon\tD750\t-600\t0\t9020,-2890,-715,-4535,12436,2348,-934,1919,7086,0,0,0\nNikon\tD780\t-600\t0\t9943,-3269,-839,-5323,13269,2259,-1198,2083,7557,0,0,0\nNikon\tD800\t0\t0\t7866,-2108,-555,-4869,12483,2681,-1176,2069,7501,0,0,0\nNikon\tD810A\t0\t0\t11973,-5685,-888,-1965,10326,1901,-115,1123,7169,0,0,0\nNikon\tD810\t0\t0\t9369,-3195,-791,-4488,12430,2301,-893,1796,6872,0,0,0\nNikon\tD850\t0\t0\t10405,-3755,-1270,-5461,13787,1793,-1040,2015,6785,0,0,0\nNikon\tD40X\t0\t0\t8819,-2543,-911,-9025,16928,2151,-1329,1213,8449,0,0,0\nNikon\tD40\t0\t0\t6992,-1668,-806,-8138,15748,2543,-874,850,7897,0,0,0\nNikon\tD50\t0\t0\t7732,-2422,-789,-8238,15884,2498,-859,783,7330,0,0,0\nNikon\tD60\t0\t0\t8736,-2458,-935,-9075,16894,2251,-1354,1242,8263,0,0,0\nNikon\tD70\t0\t0\t7732,-2422,-789,-8238,15884,2498,-859,783,7330,0,0,0\nNikon\tD80\t0\t0\t8629,-2410,-883,-9055,16940,2171,-1490,1363,8520,0,0,0\nNikon\tD90\t0\t3840\t7309,-1403,-519,-8474,16008,2622,-2434,2826,8064,0,0,0\nNikon\tD1H\t0\t0\t7659,-2238,-935,-8942,16969,2004,-2701,3051,8690,0,0,0",
        "Nikon\tD1X\t0\t0\t7702,-2245,-975,-9114,17242,1875,-2679,3055,8521,0,0,0\nNikon\tD1\t0\t0\t7637,-2199,-974,-9109,17099,2043,-2822,3306,8372,0,0,0\nNikon\tD2H\t0\t0\t5733,-911,-629,-7967,15987,2055,-3050,4013,7048,0,0,0\nNikon\tD2X\t0\t0\t10231,-2768,-1254,-8302,15900,2551,-797,681,7148,0,0,0\nNikon\tD3S\t0\t0\t8828,-2406,-694,-4874,12603,2541,-660,1509,7587,0,0,0\nNikon\tD3X\t0\t0\t7171,-1986,-648,-8085,15555,2718,-2170,2512,7457,0,0,0\nNikon\tD3\t0\t0\t8139,-2171,-663,-8747,16541,2295,-1925,2008,8093,0,0,0\nNikon\tD4\t0\t0\t8598,-2848,-857,-5618,13606,2195,-1002,1773,7137,0,0,0\nNikon\tD5\t0\t0\t9200,-3522,-992,-5755,13803,2117,-753,1486,6338,0,0,0\nNikon\tD6\t0\t0\t9028,-3423,-1035,-6321,14265,2217,-1013,1683,6928,0,0,0\nNikon\tDf\t0\t0\t8598,-2848,-857,-5618,13606,2195,-1002,1773,7137,0,0,0\nNikon\tZ 30\t0\t0\t10339,-3822,-890,-4183,12023,2436,-671,1638,7049,0,0,0\nNikon\tZ 50\t0\t0\t11640,-4829,-1079,-5107,13006,2325,-972,1711,7380,0,0,0\nNikon\tZ 5\t0\t0\t8695,-2558,-648,-5015,12711,2575,-1279,2215,7514,0,0,0\nNikon\tZ6_3\t0\t0\t11206,-4286,-941,-4879,12847,2251,-745,1654,7374,0,0,0\nNikon\tZ 6\t0\t0\t9943,-3269,-839,-5323,13269,2259,-1198,2083,7557,0,0,0\nNikon\tZ 7\t0\t0\t13705,-6004,-1400,-5464,13568,2062,-940,1706,7618,0,0,0\nNikon\tZ 8\t0\t0\t11423,-4564,-1123,-4816,12895,2119,-210,1061,7282,0,0,0\nNikon\tZ 9\t0\t0\t13389,-6049,-1441,-4544,12757,1969,229,498,7390,0,0,0\nNikon\tZ fc\t0\t0\t11640,-4829,-1079,-5107,13006,2325,-972,1711,7380,0,0,0\nNikon\tZ f\t0\t0\t11607,-4491,-977,-4522,12460,2304,-458,1519,7616,0,0,0\nOlympus\tAIR A01\t0\t4065\t8992,-3093,-639,-2563,10721,2122,-437,1270,5473,0,0,0\nOlympus\tC-5050Z\t0\t0\t10633,-3234,-1285,-7460,15570,1967,-1917,2510,6299,0,0,0\nOlympus\tC-5060WZ\t0\t0\t10445,-3362,-1307,-7662,15690,2058,-1135,1176,7602,0,0,0\nOlympus\tC-7070WZ\t0\t0\t10252,-3531,-1095,-7114,14850,2436,-1451,1723,6365,0,0,0\nOlympus\tC-7000Z\t0\t0\t10793,-3791,-1146,-7498,15177,2488,-1390,1577,7321,0,0,0\nOlympus\tC-8080WZ\t0\t0\t8606,-2509,-1014,-8238,15714,2703,-942,979,7760,0,0,0\nOlympus\tE-300\t0\t0\t7828,-1761,-348,-5788,14071,1830,-2853,4518,6557,0,0,0\nOlympus\tE-330\t0\t0\t8961,-2473,-1084,-7979,15990,2067,-2319,3035,8249,0,0,0\nOlympus\tE-400\t0\t0\t6169,-1483,-21,-7107,14761,2536,-2904,3580,8568,0,0,0\nOlympus\tE-410\t0\t3946\t8856,-2582,-1026,-7761,15766,2082,-2009,2575,7469,0,0,0\nOlympus\tE-420\t0\t4055\t8746,-2425,-1095,-7594,15612,2073,-1780,2309,7416,0,0,0\nOlympus\tE-450\t0\t4050\t8745,-2425,-1095,-7594,15613,2073,-1780,2309,7416,0,0,0\nOlympus\tE-500\t0\t0\t8136,-1968,-299,-5481,13742,1871,-2556,4205,6630,0,0,0\nOlympus\tE-510\t0\t3946\t8785,-2529,-1033,-7639,15624,2112,-1783,2300,7817,0,0,0\nOlympus\tE-520\t0\t4050\t8344,-2322,-1020,-7596,15635,2048,-1748,2269,7287,0,0,0\nOlympus\tE-600\t0\t4015\t8453,-2198,-1092,-7609,15681,2008,-1725,2337,7824,0,0,0\nOlympus\tE-620\t0\t4015\t8453,-2198,-1092,-7609,15681,2008,-1725,2337,7824,0,0,0\nOlympus\tE-10\t0\t1023\t12970,-4703,-1433,-7466,15843,1644,-2191,2451,6668,0,0,0\nOlympus\tE-20\t0\t1023\t13414,-4950,-1517,-7166,15293,1960,-2325,2664,7212,0,0,0",
        "Olympus\tE-30\t0\t4028\t8144,-1861,-1111,-7763,15894,1929,-1865,2542,7607,0,0,0\nOlympus\tE-1\t0\t0\t11846,-4767,-945,-7027,15878,1089,-2699,4122,8311,0,0,0\nOlympus\tE-3\t0\t3993\t9487,-2875,-1115,-7533,15606,2010,-1618,2100,7389,0,0,0\nOlympus\tE-5\t0\t3820\t11200,-3783,-1325,-4576,12593,2206,-695,1742,7504,0,0,0\nOlympus\tE-P1\t0\t4093\t8343,-2050,-1021,-7715,15705,2103,-1831,2380,8235,0,0,0\nOlympus\tE-P2\t0\t4093\t8343,-2050,-1021,-7715,15705,2103,-1831,2380,8235,0,0,0\nOlympus\tE-P3\t0\t0\t7575,-2159,-571,-3722,11341,2725,-1434,2819,6271,0,0,0\nOlympus\tE-P5\t0\t0\t8380,-2630,-639,-2887,10725,2496,-627,1427,5438,0,0,0\nOlympus\tE-P7\t0\t0\t9476,-3182,-765,-2613,10958,1893,-449,1315,5268,0,0,0\nOlympus\tE-PL10\t0\t0\t9197,-3190,-659,-2606,10830,2039,-458,1250,5458,0,0,0\nOlympus\tE-PL1s\t0\t0\t11409,-3872,-1393,-4572,12757,2003,-709,1810,7415,0,0,0\nOlympus\tE-PL1\t0\t0\t11408,-4289,-1215,-4286,12385,2118,-387,1467,7787,0,0,0\nOlympus\tE-PL2\t0\t3315\t15030,-5552,-1806,-3987,12387,1767,-592,1670,7023,0,0,0\nOlympus\tE-PL3\t0\t0\t7575,-2159,-571,-3722,11341,2725,-1434,2819,6271,0,0,0\nOlympus\tE-PL5\t0\t4043\t8380,-2630,-639,-2887,10725,2496,-627,1427,5438,0,0,0\nOlympus\tE-PL6\t0\t0\t8380,-2630,-639,-2887,10725,2496,-627,1427,5438,0,0,0\nOlympus\tE-PL7\t0\t0\t9197,-3190,-659,-2606,10830,2039,-458,1250,5458,0,0,0\nOlympus\tE-PL8\t0\t0\t9197,-3190,-659,-2606,10830,2039,-458,1250,5458,0,0,0\nOlympus\tE-PL9\t0\t0\t8380,-2630,-639,-2887,10725,2496,-627,1427,5438,0,0,0\nOlympus\tE-PM1\t0\t0\t7575,-2159,-571,-3722,11341,2725,-1434,2819,6271,0,0,0\nOlympus\tE-PM2\t0\t0\t8380,-2630,-639,-2887,10725,2496,-627,1427,5438,0,0,0\nOlympus\tE-M10 Mark IV\t0\t0\t9476,-3182,-765,-2613,10958,1893,-449,1315,5268,0,0,0\nOlympus\tE-M10\t0\t0\t8380,-2630,-639,-2887,10725,2496,-627,1427,5438,0,0,0\nOlympus\tE-M1X\t0\t0\t11896,-5110,-1076,-3181,11378,2048,-519,1224,5166,0,0,0\nOlympus\tE-M1 Mark III\t0\t0\t11896,-5110,-1076,-3181,11378,2048,-519,1224,5166,0,0,0\nOlympus\tE-M1 Mark II\t0\t0\t9383,-3170,-763,-2457,10702,2020,-384,1236,5552,0,0,0\nOlympus\tE-M1\t0\t0\t7687,-1984,-606,-4327,11928,2721,-1381,2339,6452,0,0,0\nOlympus\tE-M5 Mark III\t0\t0\t11896,-5110,-1076,-3181,11378,2048,-519,1224,5166,0,0,0\nOlympus\tE-M5 Mark II\t0\t0\t9422,-3258,-711,-2655,10898,2015,-512,1354,5512,0,0,0\nOlympus\tE-M5\t0\t4065\t8380,-2630,-639,-2887,10725,2496,-627,1427,5438,0,0,0\nOlympus\tPEN-F\t0\t0\t9476,-3182,-765,-2613,10958,1893,-449,1315,5268,0,0,0\nOlympus\tSH-2\t0\t0\t10156,-3425,-1077,-2611,11177,1624,-385,1592,5080,0,0,0\nOlympus\tSP-350\t0\t0\t12078,-4836,-1069,-6671,14306,2578,-786,939,7418,0,0,0\nOlympus\tSP-3\t0\t0\t11766,-4445,-1067,-6901,14421,2707,-1029,1217,7572,0,0,0\nOlympus\tSP-500UZ\t0\t4095\t9493,-3415,-666,-5211,12334,3260,-1548,2262,6482,0,0,0\nOlympus\tSP-510UZ\t0\t4094\t10593,-3607,-1010,-5881,13127,3084,-1200,1805,6721,0,0,0\nOlympus\tSP-550UZ\t0\t4094\t11597,-4006,-1049,-5432,12799,2957,-1029,1750,6516,0,0,0\nOlympus\tSP-560UZ\t0\t4089\t10915,-3677,-982,-5587,12986,2911,-1168,1968,6223,0,0,0\nOlympus\tSP-565UZ\t0\t0\t11856,-4469,-1159,-4814,12368,2756,-993,1779,5589,0,0,0\nOlympus\tSP-570UZ\t0\t0\t11522,-4044,-1146,-4736,12172,2904,-988,1829,6039,0,0,0",
        "Olympus\tSTYLUS 1\t0\t0\t8360,-2420,-880,-3928,12353,1739,-1381,2416,5173,0,0,0\nOlympus\tTG-4\t0\t0\t11426,-4159,-1126,-2066,10678,1593,-120,1327,4998,0,0,0\nOlympus\tTG-\t0\t0\t10899,-3833,-1082,-2112,10736,1575,-267,1452,5269,0,0,0\nOlympus\tXZ-10\t0\t0\t9777,-3483,-925,-2886,11297,1800,-602,1663,5134,0,0,0\nOlympus\tXZ-1\t0\t0\t10901,-4095,-1074,-1141,9208,2293,-62,1417,5158,0,0,0\nOlympus\tXZ-2\t0\t0\t9777,-3483,-925,-2886,11297,1800,-602,1663,5134,0,0,0\nOlympus\tOM-1\t0\t0\t9488,-3984,-714,-2887,10945,2229,-137,960,5786,0,0,0\nOlympus\tOM-5\t0\t0\t11896,-5110,-1076,-3181,11378,2048,-519,1224,5166,0,0,0\nOmniVison\t\t16\t1023\t12782,-4059,-379,-478,9066,1413,1340,1513,5176,0,0,0\nPentax\t*istDL2\t0\t0\t10504,-2438,-1189,-8603,16207,2531,-1022,863,12242,0,0,0\nPentax\t*istDL\t0\t0\t10829,-2838,-1115,-8339,15817,2696,-837,680,11939,0,0,0\nPentax\t*istDS2\t0\t0\t10504,-2438,-1189,-8603,16207,2531,-1022,863,12242,0,0,0\nPentax\t*istDS\t0\t0\t10371,-2333,-1206,-8688,16231,2602,-1230,1116,11282,0,0,0\nPentax\t*istD\t0\t0\t9651,-2059,-1189,-8881,16512,2487,-1460,1345,10687,0,0,0\nPentax\tK-01\t0\t0\t8134,-2728,-645,-4365,11987,2694,-838,1509,6498,0,0,0\nPentax\tK10D\t0\t0\t9679,-2965,-811,-8622,16514,2182,-975,883,9793,0,0,0\nPentax\tK1\t0\t0\t11095,-3157,-1324,-8377,15834,2720,-1108,947,11688,0,0,0\nPentax\tK20D\t0\t0\t9427,-2714,-868,-7493,16092,1373,-2199,3264,7180,0,0,0\nPentax\tK200D\t0\t0\t9186,-2678,-907,-8693,16517,2260,-1129,1094,8524,0,0,0\nPentax\tK-m\t0\t0\t9730,-2989,-970,-8527,16258,2381,-1060,970,8362,0,0,0\nPentax\tKF\t0\t0\t8766,-3149,-747,-3976,11943,2292,-517,1259,5552,0,0,0\nPentax\tKP\t0\t0\t8617,-3228,-1034,-4674,12821,2044,-803,1577,5728,0,0,0\nPentax\tK-x\t0\t0\t8843,-2837,-625,-5025,12644,2668,-411,1234,7410,0,0,0\nPentax\tK-r\t0\t0\t9895,-3077,-850,-5304,13035,2521,-883,1768,6936,0,0,0\nPentax\tK-1\t0\t0\t8596,-2981,-639,-4202,12046,2431,-685,1424,6122,0,0,0\nPentax\tK-30\t0\t0\t8134,-2728,-645,-4365,11987,2694,-838,1509,6498,0,0,0\nPentax\tK-3 Mark III\t0\t0\t9251,-3817,-1069,-4627,12667,2175,-798,1660,5633,0,0,0\nPentax\tK-3\t0\t0\t7415,-2052,-721,-5186,12788,2682,-1446,2157,6773,0,0,0\nPentax\tK-500\t0\t0\t8109,-2740,-608,-4593,12175,2731,-1006,1515,6545,0,0,0\nPentax\tK-50\t0\t0\t8109,-2740,-608,-4593,12175,2731,-1006,1515,6545,0,0,0\nPentax\tK-5 II\t0\t0\t8170,-2725,-639,-4440,12017,2744,-771,1465,6599,0,0,0\nPentax\tK-5\t0\t0\t8713,-2833,-743,-4342,11900,2772,-722,1543,6247,0,0,0\nPentax\tK-70\t0\t0\t8766,-3149,-747,-3976,11943,2292,-517,1259,5552,0,0,0\nPentax\tK-7\t0\t0\t9142,-2947,-678,-8648,16967,1663,-2224,2898,8615,0,0,0\nPentax\tK-S1\t0\t0\t8512,-3211,-787,-4167,11966,2487,-638,1288,6054,0,0,0\nPentax\tK-S2\t0\t0\t8662,-3280,-798,-3928,11771,2444,-586,1232,6054,0,0,0\nPentax\tQ-S1\t0\t0\t12995,-5593,-1107,-1879,10139,2027,-64,1233,4919,0,0,0\nPentax\tQ7\t0\t0\t10901,-3938,-1025,-2743,11210,1738,-823,1805,5344,0,0,0\nPentax\tQ10\t0\t0\t11562,-4183,-1172,-2357,10919,1641,-582,1726,5112,0,0,0\nPentax\tQ\t0\t0\t11731,-4169,-1267,-2015,10727,1473,-217,1492,4870,0,0,0",
        "Pentax\tMX-1\t0\t0\t9296,-3146,-888,-2860,11287,1783,-618,1698,5151,0,0,0\nPentax\t645D\t0\t15872\t10646,-3593,-1158,-3329,11699,1831,-667,2874,6287,0,0,0\nPentax\t645Z\t0\t0\t9519,-3591,-664,-4074,11725,2671,-624,1501,6653,0,0,0\nPanasonic\tDC-S1R\t0\t0\t11822,-5321,-1249,-5958,15114,766,-614,1264,7043,0,0,0\nPanasonic\tDC-S1H\t0\t0\t9397,-3719,-805,-5425,13326,2309,-972,1715,6034,0,0,0\nPanasonic\tDC-S1\t0\t0\t9744,-3905,-779,-4899,12807,2324,-798,1630,5827,0,0,0\nPanasonic\tDC-S5M2\t0\t0\t10308,-4206,-783,-4088,12102,2229,-125,1051,5912,0,0,0\nPanasonic\tDC-S5\t0\t0\t9744,-3905,-779,-4899,12807,2324,-798,1630,5827,0,0,0\nPanasonic\tDC-S9\t0\t0\t9983,-3890,-841,-4180,12164,2263,-249,1139,5766,0,0,0\nPanasonic\tDMC-CM1\t-15\t0\t8770,-3194,-820,-2871,11281,1803,-513,1552,4434,0,0,0\nPanasonic\tDC-FZ1000M2\t-15\t0\t9803,-4185,-992,-4066,12578,1628,-838,1824,5288,0,0,0\nPanasonic\tDMC-FZ1000\t-15\t0\t7830,-2696,-763,-3325,11667,1866,-641,1712,4824,0,0,0\nPanasonic\tDMC-FZ2500\t-15\t0\t7386,-2443,-743,-3437,11864,1757,-608,1660,4766,0,0,0\nPanasonic\tDMC-FZ100\t-15\t4095\t16197,-6146,-1761,-2393,10765,1869,366,2238,5248,0,0,0\nPanasonic\tDMC-FZ150\t-15\t4095\t11904,-4541,-1189,-2355,10899,1662,-296,1586,4289,0,0,0\nPanasonic\tDMC-FZ200\t-15\t4095\t8112,-2563,-740,-3730,11784,2197,-941,2075,4933,0,0,0\nPanasonic\tDMC-FZ300\t-15\t4095\t8378,-2798,-769,-3068,11410,1877,-538,1792,4623,0,0,0\nPanasonic\tDMC-FZ18\t0\t0\t9932,-3060,-935,-5809,13331,2753,-1267,2155,5575,0,0,0\nPanasonic\tDMC-FZ28\t-15\t3990\t10109,-3488,-993,-5412,12812,2916,-1305,2140,5543,0,0,0\nPanasonic\tDMC-FZ30\t0\t3988\t10976,-4029,-1141,-7918,15491,2600,-1670,2071,8246,0,0,0\nPanasonic\tDMC-FZ35\t-15\t0\t9938,-2780,-890,-4604,12393,2480,-1117,2304,4620,0,0,0\nPanasonic\tDMC-FZ40\t-15\t0\t13639,-5535,-1371,-1698,9633,2430,316,1152,4108,0,0,0\nPanasonic\tDMC-FZ50\t0\t0\t7906,-2709,-594,-6231,13351,3220,-1922,2631,6537,0,0,0\nPanasonic\tDMC-FZ70\t-15\t0\t11532,-4324,-1066,-2375,10847,1749,-564,1699,4351,0,0,0\nPanasonic\tDC-FZ80\t-15\t0\t8550,-2908,-842,-3195,11529,1881,-338,1603,4631,0,0,0\nPanasonic\tDMC-FZ8\t0\t3967\t8986,-2755,-802,-6341,13575,3077,-1476,2144,6379,0,0,0\nPanasonic\tDMC-L10\t-15\t3990\t8025,-1942,-1050,-7920,15904,2100,-2456,3005,7039,0,0,0\nPanasonic\tDMC-L1\t0\t3967\t8054,-1885,-1025,-8349,16367,2040,-2805,3542,7629,0,0,0\nPanasonic\tDMC-LC1\t0\t0\t11340,-4069,-1275,-7555,15266,2448,-2960,3426,7685,0,0,0\nPanasonic\tDMC-LF1\t-15\t0\t9379,-3267,-816,-3227,11560,1881,-926,1928,5340,0,0,0\nPanasonic\tDC-LX100M2\t-15\t0\t8585,-3127,-833,-4005,12250,1953,-650,1494,4862,0,0,0\nPanasonic\tDMC-LX100\t-15\t0\t8844,-3538,-768,-3709,11762,2200,-698,1792,5220,0,0,0\nPanasonic\tDMC-LX1\t0\t3967\t10704,-4187,-1230,-8314,15952,2501,-920,945,8927,0,0,0\nPanasonic\tDMC-LX2\t0\t0\t8048,-2810,-623,-6450,13519,3272,-1700,2146,7049,0,0,0\nPanasonic\tDMC-LX3\t-15\t0\t8128,-2668,-655,-6134,13307,3161,-1782,2568,6083,0,0,0\nPanasonic\tDMC-LX5\t-15\t0\t10909,-4295,-948,-1333,9306,2399,22,1738,4582,0,0,0\nPanasonic\tDMC-LX7\t-15\t0\t10148,-3743,-991,-2837,11366,1659,-701,1893,4899,0,0,0\nPanasonic\tDMC-LX9\t-15\t0\t7790,-2736,-755,-3452,11870,1769,-628,1647,4898,0,0,0\nPanasonic\tDMC-FX150\t-15\t4095\t9082,-2907,-925,-6119,13377,3058,-1797,2641,5609,0,0,0\nPanasonic\tDC-G99\t-15\t0\t9657,-3963,-748,-3361,11378,2258,-568,1415,5158,0,0,0",
        "Panasonic\tDC-G100\t-15\t0\t8370,-2869,-710,-3389,11372,2298,-640,1599,4887,0,0,0\nPanasonic\tDMC-G10\t0\t0\t10113,-3400,-1114,-4765,12683,2317,-377,1437,6710,0,0,0\nPanasonic\tDMC-G1\t-15\t3988\t8199,-2065,-1056,-8124,16156,2033,-2458,3022,7220,0,0,0\nPanasonic\tDMC-G2\t-15\t3900\t10113,-3400,-1114,-4765,12683,2317,-377,1437,6710,0,0,0\nPanasonic\tDMC-G3\t-15\t4095\t6763,-1919,-863,-3868,11515,2684,-1216,2387,5879,0,0,0\nPanasonic\tDMC-G5\t-15\t4095\t7798,-2562,-740,-3879,11584,2613,-1055,2248,5434,0,0,0\nPanasonic\tDMC-G6\t-15\t4095\t8294,-2891,-651,-3869,11590,2595,-1183,2267,5352,0,0,0\nPanasonic\tDMC-G7\t-15\t4095\t7610,-2780,-576,-4614,12195,2733,-1375,2393,6490,0,0,0\nPanasonic\tDMC-G8\t-15\t4095\t7610,-2780,-576,-4614,12195,2733,-1375,2393,6490,0,0,0\nPanasonic\tDC-G9M2\t0\t0\t8325,-3456,-623,-4330,12089,2528,-860,2646,5984,0,0,0\nPanasonic\tDC-G9\t-15\t0\t7685,-2375,-634,-3687,11700,2249,-748,1546,5111,0,0,0\nPanasonic\tDMC-GH1\t-15\t3986\t6299,-1466,-532,-6535,13852,2969,-2331,3112,5984,0,0,0\nPanasonic\tDMC-GH2\t-15\t3989\t7780,-2410,-806,-3913,11724,2484,-1018,2390,5298,0,0,0\nPanasonic\tDMC-GH3\t-15\t0\t6559,-1752,-491,-3672,11407,2586,-962,1875,5130,0,0,0\nPanasonic\tDMC-GH4\t-15\t0\t7122,-2108,-512,-3155,11201,2231,-541,1423,5045,0,0,0\nPanasonic\tDC-GH5s\t-15\t0\t6929,-2355,-708,-4192,12534,1828,-1097,1989,5195,0,0,0\nPanasonic\tDC-GH5M2\t0\t0\t9300,-3659,-755,-2981,10988,2287,-190,1077,5016,0,0,0\nPanasonic\tDC-GH5\t-15\t0\t7641,-2336,-605,-3218,11299,2187,-485,1338,5121,0,0,0\nPanasonic\tDC-GH6\t-2048\t0\t7949,-3491,-710,-3435,11681,1977,-503,1622,5065,0,0,0\nPanasonic\tDC-GH7\t0\t0\t8573,-3575,-678,-4252,12079,2451,-808,2524,5936,0,0,0\nPanasonic\tDMC-GM1\t-15\t0\t6770,-1895,-744,-5232,13145,2303,-1664,2691,5703,0,0,0\nPanasonic\tDMC-GM5\t-15\t0\t8238,-3244,-679,-3921,11814,2384,-836,2022,5852,0,0,0\nPanasonic\tDC-GF10\t-15\t0\t7610,-2780,-576,-4614,12195,2733,-1375,2393,6490,0,0,0\nPanasonic\tDMC-GF1\t-15\t3986\t7888,-1902,-1011,-8106,16085,2099,-2353,2866,7330,0,0,0\nPanasonic\tDMC-GF2\t-15\t4095\t7888,-1902,-1011,-8106,16085,2099,-2353,2866,7330,0,0,0\nPanasonic\tDMC-GF3\t-15\t4095\t9051,-2468,-1204,-5212,13276,2121,-1197,2510,6890,0,0,0\nPanasonic\tDMC-GF5\t-15\t4095\t8228,-2945,-660,-3938,11792,2430,-1094,2278,5793,0,0,0\nPanasonic\tDMC-GF6\t-15\t0\t8130,-2801,-946,-3520,11289,2552,-1314,2511,5791,0,0,0\nPanasonic\tDMC-GF7\t-15\t0\t7610,-2780,-576,-4614,12195,2733,-1375,2393,6490,0,0,0\nPanasonic\tDMC-GF8\t-15\t0\t7610,-2780,-576,-4614,12195,2733,-1375,2393,6490,0,0,0\nPanasonic\tDC-GF9\t-15\t0\t7610,-2780,-576,-4614,12195,2733,-1375,2393,6490,0,0,0\nPanasonic\tDMC-GX85\t-15\t0\t7771,-3020,-629,-4029,11950,2345,-821,1977,6119,0,0,0\nPanasonic\tDMC-GX1\t-15\t0\t6763,-1919,-863,-3868,11515,2684,-1216,2387,5879,0,0,0\nPanasonic\tDMC-GX7\t-15\t0\t7610,-2780,-576,-4614,12195,2733,-1375,2393,6490,0,0,0\nPanasonic\tDMC-GX8\t-15\t0\t7564,-2263,-606,-3148,11239,2177,-540,1435,4853,0,0,0\nPanasonic\tDC-GX9\t-15\t0\t7564,-2263,-606,-3148,11239,2177,-540,1435,4853,0,0,0\nPanasonic\tDMC-ZS100\t-15\t0\t7790,-2736,-755,-3452,11870,1769,-628,1647,4898,0,0,0\nPanasonic\tDC-ZS200\t-15\t0\t7790,-2736,-755,-3452,11870,1769,-628,1647,4898,0,0,0\nPanasonic\tDMC-ZS40\t-15\t0\t8607,-2822,-808,-3755,11930,2049,-820,2060,5224,0,0,0\nPanasonic\tDMC-ZS50\t-15\t0\t8802,-3135,-789,-3151,11468,1904,-550,1745,4810,0,0,0",
        "Panasonic\tDMC-ZS60\t-15\t0\t8550,-2908,-842,-3195,11529,1881,-338,1603,4631,0,0,0\nPanasonic\tDC-ZS70\t-15\t0\t9052,-3117,-883,-3045,11346,1927,-205,1520,4730,0,0,0\nPanasonic\tDC-ZS80\t-15\t0\t12194,-5340,-1329,-3035,11394,1858,-50,1418,5219,0,0,0\nPhase One\tH20\t0\t0\t3906,1422,-467,-9953,18472,1365,-3307,4496,6406,0,0,0\nPhase One\tH25\t0\t0\t2905,732,-237,-8134,16626,1476,-3038,4253,7517,0,0,0\nPhase One\tIQ4 150MP\t0\t0\t6644,-2257,-804,-6459,14562,2019,-1221,1876,6411,0,0,0\nPhase One\tIQ140\t0\t0\t8035,435,-962,-6001,13872,2320,-1159,3065,5434,0,0,0\nPhase One\tIQ150\t0\t0\t10325,845,-604,-4113,13385,481,-1791,4163,6924,0,0,0\nPhase One\tIQ160\t0\t0\t8035,435,-962,-6001,13872,2320,-1159,3065,5434,0,0,0\nPhase One\tIQ180\t0\t0\t6294,686,-712,-5435,13417,2211,-1006,2435,5042,0,0,0\nPhase One\tIQ250\t0\t0\t10325,845,-604,-4113,13385,481,-1791,4163,6924,0,0,0\nPhase One\tIQ260\t0\t0\t8035,435,-962,-6001,13872,2320,-1159,3065,5434,0,0,0\nPhase One\tIQ280\t0\t0\t6294,686,-712,-5435,13417,2211,-1006,2435,5042,0,0,0\nPhase One\tIQ3 100MP\t0\t0\t10999,354,-742,-4590,13342,937,-1060,2166,8120,0,0,0\nPhase One\tIQ3 50MP\t0\t0\t10058,1079,-587,-4135,12903,944,-916,2726,7480,0,0,0\nPhase One\tIQ3 60MP\t0\t0\t8035,435,-962,-6001,13872,2320,-1159,3065,5434,0,0,0\nPhase One\tIQ3 80MP\t0\t0\t6294,686,-712,-5435,13417,2211,-1006,2435,5042,0,0,0\nPhase One\tP21\t0\t0\t6516,-2050,-507,-8217,16703,1479,-3492,4741,8489,0,0,0\nPhase One\tP30\t0\t0\t4516,-244,-36,-7020,14976,2174,-3206,4670,7087,0,0,0\nPhase One\tP40\t0\t0\t8035,435,-962,-6001,13872,2320,-1159,3065,5434,0,0,0\nPhase One\tP45\t0\t0\t5053,-24,-117,-5685,14077,1703,-2619,4491,5850,0,0,0\nPhase One\tP65\t0\t0\t8035,435,-962,-6001,13872,2320,-1159,3065,5434,0,0,0\nPhase One\tP2\t0\t0\t2905,732,-237,-8134,16626,1476,-3038,4253,7517,0,0,0\nPhotron\tBC2-HD\t0\t0\t14603,-4122,-528,-1810,9794,2017,-297,2763,5936,0,0,0\nPolaroid\tx530\t0\t0\t13458,-2556,-510,-5444,15081,205,0,0,12120,0,0,0\nRed\tOne\t704\t65535\t21014,-7891,-2613,-3056,12201,856,-2203,5125,8042,0,0,0\nRicoh\tS10 24-72mm F2.5-4.4 VC\t0\t0\t10531,-4043,-878,-2038,10270,2052,-107,895,4577,0,0,0\nRicoh\tGR A12 50mm F2.5 MACRO\t0\t0\t8849,-2560,-689,-5092,12831,2520,-507,1280,7104,0,0,0\nRicoh\tGR DIGITAL 2\t0\t0\t8846,-2704,-729,-5265,12708,2871,-1471,1955,6218,0,0,0\nRicoh\tGR DIGITAL 3\t0\t0\t8170,-2496,-655,-5147,13056,2312,-1367,1859,5265,0,0,0\nRicoh\tGR DIGITAL 4\t0\t0\t8771,-3151,-837,-3097,11015,2389,-703,1343,4924,0,0,0\nRicoh\tGR III\t0\t0\t6127,-1777,-585,-5913,13699,2428,-1088,1780,6017,0,0,0\nRicoh\tGR II\t0\t0\t5329,-1459,-390,-5407,12930,2768,-1119,1772,6046,0,0,0\nRicoh\tGR\t0\t0\t5329,-1459,-390,-5407,12930,2768,-1119,1772,6046,0,0,0\nRicoh\tGX200\t0\t0\t8040,-2368,-626,-4659,12543,2363,-1125,1581,5660,0,0,0\nRicoh\tGXR Mount A12\t0\t0\t7834,-2182,-739,-5453,13409,2241,-952,2005,6620,0,0,0\nRicoh\tGXR A12 50mm\t0\t0\t8849,-2560,-689,-5092,12831,2520,-507,1280,7104,0,0,0\nRicoh\tGXR A12 28mm\t0\t0\t10228,-3159,-933,-5304,13158,2371,-943,1873,6685,0,0,0\nRicoh\tGXR A16\t0\t0\t7837,-2538,-730,-4370,12184,2461,-868,1648,5830,0,0,0\nRicoh\tGXR P10\t0\t0\t13168,-5128,-1663,-3006,11569,1611,-373,1244,4907,0,0,0",
        "Ricoh\tGXR S10\t0\t0\t8963,-2926,-754,-4881,12921,2164,-1464,1944,4901,0,0,0\nSamsung\tEX1\t0\t15872\t8898,-2498,-994,-3144,11328,2066,-760,1381,4576,0,0,0\nSamsung\tEX2F\t0\t2047\t10648,-3897,-1055,-2022,10573,1668,-492,1611,4742,0,0,0\nSamsung\tGalaxy S6\t0\t0\t13699,-5767,-1384,-4449,13879,499,-467,1691,5892,0,0,0\nSamsung\tGalaxy S7\t0\t0\t9927,-3704,-1024,-3935,12758,1257,-389,1512,4993,0,0,0\nSamsung\tGalaxy S8\t0\t0\t9927,-3704,-1024,-3935,12758,1257,-389,1512,4993,0,0,0\nSamsung\tGalaxy S9\t0\t0\t13292,-6142,-1268,-4095,12890,1283,-557,1930,5163,0,0,0\nSamsung\tGalaxy Note 9 Rear Camera\t0\t0\t13292,-6142,-1268,-4095,12890,1283,-557,1930,5163,0,0,0\nSamsung\tNX U\t0\t0\t7557,-2522,-739,-4679,12949,1894,-840,1777,5311,0,0,0\nSamsung\tNX3300\t0\t0\t8060,-2933,-761,-4504,12890,1762,-630,1489,5227,0,0,0\nSamsung\tNX3000\t0\t0\t8060,-2933,-761,-4504,12890,1762,-630,1489,5227,0,0,0\nSamsung\tNX30\t0\t0\t7557,-2522,-739,-4679,12949,1894,-840,1777,5311,0,0,0\nSamsung\tNX2000\t0\t0\t7557,-2522,-739,-4679,12949,1894,-840,1777,5311,0,0,0\nSamsung\tNX2\t0\t4095\t6933,-2268,-753,-4921,13387,1647,-803,1641,6096,0,0,0\nSamsung\tNX1000\t0\t0\t6933,-2268,-753,-4921,13387,1647,-803,1641,6096,0,0,0\nSamsung\tNX1100\t0\t0\t6933,-2268,-753,-4921,13387,1647,-803,1641,6096,0,0,0\nSamsung\tNX11\t0\t0\t10332,-3234,-1168,-6111,14639,1520,-1352,2647,8331,0,0,0\nSamsung\tNX10\t0\t0\t10332,-3234,-1168,-6111,14639,1520,-1352,2647,8331,0,0,0\nSamsung\tNX500\t0\t0\t10686,-4042,-1052,-3595,13238,276,-464,1259,5931,0,0,0\nSamsung\tNX5\t0\t0\t10332,-3234,-1168,-6111,14639,1520,-1352,2647,8331,0,0,0\nSamsung\tNX1\t0\t0\t10686,-4042,-1052,-3595,13238,276,-464,1259,5931,0,0,0\nSamsung\tNX mini\t0\t0\t5222,-1196,-550,-6540,14649,2009,-1666,2819,5657,0,0,0\nSamsung\tWB2000\t0\t4095\t12093,-3557,-1155,-1000,9534,1733,-22,1787,4576,0,0,0\nSamsung\tWB5000\t0\t0\t7675,-2195,-305,-5860,14118,1857,-2425,4007,6578,0,0,0\nSamsung\tS85\t0\t0\t11885,-3968,-1473,-4214,12299,1916,-835,1655,5549,0,0,0\nSigma\tdp0 Quattro\t2047\t0\t13801,-3390,-1016,5535,3802,877,1848,4245,3730,0,0,0\nSigma\tdp1 Quattro\t2047\t0\t13801,-3390,-1016,5535,3802,877,1848,4245,3730,0,0,0\nSigma\tdp2 Quattro\t2047\t0\t13801,-3390,-1016,5535,3802,877,1848,4245,3730,0,0,0\nSigma\tdp3 Quattro\t2047\t0\t13801,-3390,-1016,5535,3802,877,1848,4245,3730,0,0,0\nSigma\tsd Quattro H\t256\t0\t1295,108,-311,256,828,-65,-28,750,254,0,0,0\nSigma\tsd Quattro\t2047\t0\t1295,108,-311,256,828,-65,-28,750,254,0,0,0\nSigma\tSD9\t15\t4095\t13564,-2537,-751,-5465,15154,194,-67,116,10425,0,0,0\nSigma\tSD10\t15\t16383\t6787,-1682,575,-3091,8357,160,217,-369,12314,0,0,0\nSigma\tSD14\t15\t16383\t13589,-2509,-739,-5440,15104,193,-61,105,10554,0,0,0\nSigma\tSD15\t15\t4095\t13556,-2537,-730,-5462,15144,195,-61,106,10577,0,0,0\nSigma\tSD1\t31\t4095\t5133,-1895,-353,4978,744,144,3837,3069,2777,0,0,0\nSigma\tDP1 Merrill\t31\t4095\t5133,-1895,-353,4978,744,144,3837,3069,2777,0,0,0\nSigma\tDP2 Merrill\t31\t4095\t5133,-1895,-353,4978,744,144,3837,3069,2777,0,0,0\nSigma\tDP3 Merrill\t31\t4095\t5133,-1895,-353,4978,744,144,3837,3069,2777,0,0,0\nSigma\tDP1X\t0\t4095\t13704,-2452,-857,-5413,15073,186,-89,151,9820,0,0,0",
        "Sigma\tDP1\t0\t4095\t12774,-2591,-394,-5333,14676,207,15,-21,12127,0,0,0\nSigma\tDP\t0\t4095\t13100,-3638,-847,6855,2369,580,2723,3218,3251,0,0,0\nSinar\t\t0\t0\t16442,-2956,-2422,-2877,12128,750,-1136,6066,4559,0,0,0\nSony\tDSC-F828\t0\t0\t7924,-1910,-777,-8226,15459,2998,-1517,2199,6818,-7242,11401,3481\nSony\tDSC-R1\t0\t0\t8512,-2641,-694,-8042,15670,2526,-1821,2117,7414,0,0,0\nSony\tDSC-V3\t0\t0\t7511,-2571,-692,-7894,15088,3060,-948,1111,8128,0,0,0\nSony\tDSC-HX9\t-800\t0\t13076,-5686,-1481,-4027,12851,1251,-167,725,4937,0,0,0\nSony\tZV-1\t-800\t0\t8280,-2987,-703,-3531,11645,2133,-550,1542,5312,0,0,0\nSony\tZV-E10M2\t0\t0\t6972,-2408,-600,-4330,12101,2515,-388,1277,5847,0,0,0\nSony\tZV-E10\t0\t0\t6355,-2067,-490,-3653,11542,2400,-406,1258,5506,0,0,0\nSony\tZV-E1\t0\t0\t6912,-2127,-469,-4470,12175,2587,-398,1478,6492,0,0,0\nSony\tDSC-RX100M7\t-800\t0\t10315,-4390,-937,-4859,12734,2365,-734,1537,5997,0,0,0\nSony\tDSC-RX100M6\t-800\t0\t7325,-2321,-596,-3494,11674,2055,-668,1562,5031,0,0,0\nSony\tDSC-RX100M5A\t-800\t0\t11176,-4700,-965,-4004,12184,2032,-763,1726,5876,0,0,0\nSony\tDSC-RX100M\t-800\t0\t6596,-2079,-562,-4782,13016,1933,-970,1581,5181,0,0,0\nSony\tDSC-RX100\t0\t0\t8651,-2754,-1057,-3464,12207,1373,-568,1398,4434,0,0,0\nSony\tDSC-RX10M4\t-800\t0\t7699,-2566,-629,-2967,11270,1928,-378,1286,4807,0,0,0\nSony\tDSC-RX10\t0\t0\t6679,-1825,-745,-5047,13256,1953,-1580,2422,5183,0,0,0\nSony\tDSC-RX1RM2\t0\t0\t6629,-1900,-483,-4618,12349,2550,-622,1381,6514,0,0,0\nSony\tDSC-RX1R\t0\t0\t6344,-1612,-462,-4863,12477,2681,-865,1786,6899,0,0,0\nSony\tDSC-RX1\t0\t0\t6344,-1612,-462,-4863,12477,2681,-865,1786,6899,0,0,0\nSony\tDSC-RX0\t-800\t0\t9396,-3507,-843,-2497,11111,1572,-343,1355,5089,0,0,0\nSony\tDSLR-A100\t0\t4075\t9437,-2811,-774,-8405,16215,2290,-710,596,7181,0,0,0\nSony\tDSLR-A290\t0\t0\t6038,-1484,-579,-9145,16746,2512,-875,746,7218,0,0,0\nSony\tDSLR-A2\t0\t0\t9847,-3091,-928,-8485,16345,2225,-715,595,7103,0,0,0\nSony\tDSLR-A300\t0\t0\t9847,-3091,-928,-8485,16345,2225,-715,595,7103,0,0,0\nSony\tDSLR-A330\t0\t0\t9847,-3091,-929,-8485,16346,2225,-714,595,7103,0,0,0\nSony\tDSLR-A3\t0\t0\t6038,-1484,-579,-9145,16746,2512,-875,746,7218,0,0,0\nSony\tDSLR-A450\t0\t0\t4950,-580,-103,-5228,12542,3029,-709,1435,7371,0,0,0\nSony\tDSLR-A580\t0\t16596\t5932,-1492,-411,-4813,12285,2856,-741,1524,6739,0,0,0\nSony\tDSLR-A500\t0\t16596\t6046,-1127,-278,-5574,13076,2786,-691,1419,7625,0,0,0\nSony\tDSLR-A5\t0\t16596\t4950,-580,-103,-5228,12542,3029,-709,1435,7371,0,0,0\nSony\tDSLR-A700\t0\t0\t5775,-805,-359,-8574,16295,2391,-1943,2341,7249,0,0,0\nSony\tDSLR-A850\t0\t0\t5413,-1162,-365,-5665,13098,2866,-608,1179,8440,0,0,0\nSony\tDSLR-A900\t0\t0\t5209,-1072,-397,-8845,16120,2919,-1618,1803,8654,0,0,0\nSony\tDSLR-A\t0\t0\t5209,-1072,-397,-8845,16120,2919,-1618,1803,8654,0,0,0\nSony\tILCA-68\t0\t0\t6435,-1903,-536,-4722,12449,2550,-663,1363,6517,0,0,0\nSony\tILCA-77M2\t0\t0\t5991,-1732,-443,-4100,11989,2381,-704,1467,5992,0,0,0\nSony\tILCA-99M2\t0\t0\t6660,-1918,-471,-4613,12398,2485,-649,1433,6447,0,0,0\nSony\tILCE-1\t0\t0\t8161,-2947,-739,-4811,12668,2389,-437,1229,6524,0,0,0",
        "Sony\tILCE-7RM5\t0\t0\t8200,-2976,-719,-4296,12053,2532,-429,1282,5774,0,0,0\nSony\tILCE-7RM4\t0\t0\t7662,-2686,-660,-5240,12965,2530,-796,1508,6167,0,0,0\nSony\tILCE-7RM3\t0\t0\t6640,-1847,-503,-5238,13010,2474,-993,1673,6527,0,0,0\nSony\tILCE-7RM2\t0\t0\t6629,-1900,-483,-4618,12349,2550,-622,1381,6514,0,0,0\nSony\tILCE-7R\t0\t0\t4913,-541,-202,-6130,13513,2906,-1564,2151,7183,0,0,0\nSony\tILCE-7SM3\t0\t0\t6912,-2127,-469,-4470,12175,2587,-398,1478,6492,0,0,0\nSony\tILCE-7S\t0\t0\t5838,-1430,-246,-3497,11477,2297,-748,1885,5778,0,0,0\nSony\tILCE-7CR\t0\t0\t8200,-2976,-719,-4296,12053,2532,-429,1282,5774,0,0,0\nSony\tILCE-7CM2\t0\t0\t7460,-2365,-588,-5687,13442,2474,-624,1156,6584,0,0,0\nSony\tILCE-7C\t0\t0\t7374,-2389,-551,-5435,13162,2519,-1006,1795,6552,0,0,0\nSony\tILCE-7M4\t0\t0\t7460,-2365,-588,-5687,13442,2474,-624,1156,6584,0,0,0\nSony\tILCE-7M3\t0\t0\t7374,-2389,-551,-5435,13162,2519,-1006,1795,6552,0,0,0\nSony\tILCE-7\t0\t0\t5271,-712,-347,-6153,13653,2763,-1601,2366,7242,0,0,0\nSony\tILCE-9M3\t0\t0\t9811,-3908,-752,-3704,11577,2417,-73,950,5980,0,0,0\nSony\tILCE-9\t0\t0\t6389,-1703,-378,-4562,12265,2587,-670,1489,6550,0,0,0\nSony\tILCE-6100\t0\t0\t7657,-2847,-607,-4083,11966,2389,-684,1418,5844,0,0,0\nSony\tILCE-6300\t0\t0\t5973,-1695,-419,-3826,11797,2293,-639,1398,5789,0,0,0\nSony\tILCE-6400\t0\t0\t7657,-2847,-607,-4083,11966,2389,-684,1418,5844,0,0,0\nSony\tILCE-6500\t0\t0\t5973,-1695,-419,-3826,11797,2293,-639,1398,5789,0,0,0\nSony\tILCE-6600\t0\t0\t7657,-2847,-607,-4083,11966,2389,-684,1418,5844,0,0,0\nSony\tILCE-6700\t0\t0\t6972,-2408,-600,-4330,12101,2515,-388,1277,5847,0,0,0\nSony\tILCE-\t0\t0\t5991,-1456,-455,-4764,12135,2980,-707,1425,6701,0,0,0\nSony\tILME-FX30\t0\t0\t6972,-2408,-600,-4330,12101,2515,-388,1277,5847,0,0,0\nSony\tILME-FX3\t0\t0\t6912,-2127,-469,-4470,12175,2587,-398,1478,6492,0,0,0\nSony\tILME-FX2\t0\t0\t7460,-2365,-588,-5687,13442,2474,-624,1156,6584,0,0,0\nSony\tILX-LR1\t0\t0\t8200,-2976,-719,-4296,12053,2532,-429,1282,5774,0,0,0\nSony\tNEX-5N\t0\t0\t5991,-1456,-455,-4764,12135,2980,-707,1425,6701,0,0,0\nSony\tNEX-5R\t0\t0\t6129,-1545,-418,-4930,12490,2743,-977,1693,6615,0,0,0\nSony\tNEX-5T\t0\t0\t6129,-1545,-418,-4930,12490,2743,-977,1693,6615,0,0,0\nSony\tNEX-5\t0\t0\t6549,-1550,-436,-4880,12435,2753,-854,1868,6976,0,0,0\nSony\tNEX-3N\t0\t0\t6129,-1545,-418,-4930,12490,2743,-977,1693,6615,0,0,0\nSony\tNEX-3\t0\t0\t6549,-1550,-436,-4880,12435,2753,-854,1868,6976,0,0,0\nSony\tNEX-6\t0\t0\t6129,-1545,-418,-4930,12490,2743,-977,1693,6615,0,0,0\nSony\tNEX-7\t0\t0\t5491,-1192,-363,-4951,12342,2948,-911,1722,7192,0,0,0\nSony\tNEX-VG30\t0\t0\t6129,-1545,-418,-4930,12490,2743,-977,1693,6615,0,0,0\nSony\tNEX-VG900\t0\t0\t6344,-1612,-462,-4863,12477,2681,-865,1786,6899,0,0,0\nSony\tNEX-\t0\t0\t5991,-1456,-455,-4764,12135,2980,-707,1425,6701,0,0,0\nSony\tSLT-A33\t0\t0\t6069,-1221,-366,-5221,12779,2734,-1024,2066,6834,0,0,0\nSony\tSLT-A35\t0\t0\t5986,-1618,-415,-4557,11820,3120,-681,1404,6971,0,0,0\nSony\tSLT-A37\t0\t0\t5991,-1456,-455,-4764,12135,2980,-707,1425,6701,0,0,0",
        "Sony\tSLT-A55\t0\t0\t5932,-1492,-411,-4813,12285,2856,-741,1524,6739,0,0,0\nSony\tSLT-A5\t0\t0\t5991,-1456,-455,-4764,12135,2980,-707,1425,6701,0,0,0\nSony\tSLT-A65\t0\t0\t5491,-1192,-363,-4951,12342,2948,-911,1722,7192,0,0,0\nSony\tSLT-A77\t0\t0\t5491,-1192,-363,-4951,12342,2948,-911,1722,7192,0,0,0\nSony\tSLT-A99\t0\t0\t6344,-1612,-462,-4863,12477,2681,-865,1786,6899,0,0,0\nSony\tSLT-A\t0\t0\t5986,-1618,-415,-4557,11820,3120,-681,1404,6971,0,0,0\nSony\tMODEL-NAME\t0\t0\t5491,-1192,-363,-4951,12342,2948,-911,1722,7192,0,0,0\nYI\tM1\t0\t0\t7712,-2059,-653,-3882,11494,2726,-710,1332,5958,0,0,0",
    )

    private val entries: List<CameraMatrixEntry> by lazy { parse() }

    private val byMaker: Map<String, List<CameraMatrixEntry>> by lazy {
        val m = LinkedHashMap<String, MutableList<CameraMatrixEntry>>()
        for (e in entries) m.getOrPut(e.maker) { ArrayList() }.add(e)
        m
    }

    private fun parse(): List<CameraMatrixEntry> {
        val out = ArrayList<CameraMatrixEntry>(808)
        for (chunk in CHUNKS) {
            for (line in chunk.split('\n')) {
                if (line.isEmpty()) continue
                val f = line.split('\t')
                val v = f[4].split(',').map { it.toInt() }
                val colors = if (v[0] == 0) 0 else if (v[9] != 0 || v[10] != 0 || v[11] != 0) 4 else 3
                val m = if (colors == 0) null else Array(colors) { r -> DoubleArray(3) { c -> v[r * 3 + c] / 10000.0 } }
                out.add(CameraMatrixEntry(f[0], f[1], f[2].toInt(), f[3].toInt(), colors, m))
            }
        }
        return out
    }
}
