#!/usr/bin/env python3
"""Regenerate engine/.../raw/CameraMatrices.kt from a LibRaw source tree.

Usage:
    python3 tools/gen_camera_matrices.py <LibRaw source dir> [output.kt]

The source dir is the LibRaw root (the one that contains `src/` and `libraw/`).
Any release of the 0.21 / 0.22 series works (the table layout with
LIBRAW_CAMERAMAKER_* maker ids is required).  The default output path is
engine/src/main/kotlin/app/spektralab/raw/CameraMatrices.kt relative to the
repository root.

What is extracted
  * src/tables/colordata.cpp   the `adobe_coeff` table
                               (maker id, model prefix, black, maximum, 3x3/4x3 matrix x10000)
  * src/metadata/identify.cpp  `CorpTable` (maker id -> canonical maker name, in match order)
  * src/metadata/normalize_model.cpp
                               the model alias tables used by GetNormalizedModel(), and its Canon
                               `unique[]` table (maker-note model id -> model name), with the ids
                               from internal/libraw_cameraids.h

Entries inside `#ifdef USE_6BY9RPI` are skipped (that macro is not defined in
the default LibRaw build).  Commented-out entries are skipped.

Credit: the table data and lookup semantics are derived from LibRaw
(Copyright LibRaw LLC, dual-licensed LGPL 2.1 / CDDL 1.0) which in turn
derives from dcraw.c by Dave Coffin.  This project is GPLv3.
"""
import os
import re
import sys

CHUNK_ENTRIES = 40          # table entries per string constant
MAX_CONST_BYTES = 30000     # hard sanity cap, JVM limit is 65535


def read(path):
    with open(path, encoding='utf-8', errors='replace') as f:
        return f.read()


def strip_comments(src):
    """Remove /* */ and // comments, respecting string literals."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1])
            i = j + 1
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            out.append(' ')
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def drop_ifdef(src, macro):
    """Remove `#ifdef macro ... #endif` blocks (non-nested use assumed)."""
    pat = re.compile(r'^[ \t]*#ifdef[ \t]+%s\b.*?^[ \t]*#endif[^\n]*\n' % re.escape(macro),
                     re.S | re.M)
    return pat.sub('', src)


def c_string(lit):
    """Decode the body of a C string literal (simple escapes only)."""
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), lit)


def c_int(tok):
    tok = tok.strip()
    return int(tok, 0)


def parse_corp_table(identify_src):
    body = strip_comments(identify_src)
    m = re.search(r'CorpTable\[\]\s*=\s*\{(.*?)\n\};', body, re.S)
    if not m:
        raise SystemExit('CorpTable not found in identify.cpp')
    rows = re.findall(r'\{\s*LIBRAW_CAMERAMAKER_(\w+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*\}', m.group(1))
    if not rows:
        raise SystemExit('CorpTable is empty')
    return [(enum, c_string(name)) for enum, name in rows]


def parse_color_table(colordata_src):
    body = strip_comments(colordata_src)
    body = drop_ifdef(body, 'USE_6BY9RPI')
    m = re.search(r'table\[\]\s*=\s*\{(.*?)\n\s*\};', body, re.S)
    if not m:
        raise SystemExit('adobe_coeff table not found')
    entry = re.compile(
        r'\{\s*LIBRAW_CAMERAMAKER_(\w+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*'
        r'([-+]?\w+)\s*,\s*(\w+)\s*,\s*\{([^}]*)\}\s*\}', re.S)
    rows = []
    for em in entry.finditer(m.group(1)):
        enum, prefix, black, white, trans = em.groups()
        vals = [c_int(v) for v in trans.split(',') if v.strip()]
        if len(vals) > 12:
            raise SystemExit('too many matrix values in %s %s' % (enum, prefix))
        vals += [0] * (12 - len(vals))
        rows.append((enum, c_string(prefix), c_int(black), c_int(white), vals))
    # sanity: every "{ LIBRAW_CAMERAMAKER_" in the code must have been parsed
    expected = len(re.findall(r'\{\s*LIBRAW_CAMERAMAKER_', m.group(1)))
    if expected != len(rows):
        raise SystemExit('parsed %d of %d table entries' % (len(rows), expected))
    return rows


def parse_alias_tables(norm_src):
    body = strip_comments(norm_src)
    tables = {}
    for m in re.finditer(r'static const char (\w+alias\w*|\w*_aliases)\[\]\[\d+\]\s*=\s*\{(.*?)\n?\s*\};', body, re.S):
        name, blk = m.groups()
        items = []
        # elements are separated by commas; adjacent literals concatenate (C rule)
        for elem in blk.split(','):
            lits = re.findall(r'"((?:[^"\\]|\\.)*)"', elem)
            if lits:
                items.append(''.join(c_string(x) for x in lits))
        tables[name] = items
    return tables


def parse_canon_ids(norm_src, ids_src):
    """GetNormalizedModel `unique[]`: Canon model id (maker note 0x0010) -> LibRaw model name, in table order."""
    ids = {}
    for name, expr in re.findall(r'#define\s+(CanonID_\w+)\s+([^\n]+)', ids_src):
        expr = re.sub(r'(0x[0-9A-Fa-f]+|\d+)ULL', r'\1', expr.split('//')[0]).strip()
        if not re.fullmatch(r'[()0-9a-fA-Fx+ ]+', expr):
            raise SystemExit('unexpected CanonID expression %r' % expr)
        ids[name] = eval(expr)
    blk = re.search(r'unique\[\]\s*=(.*?)\};', strip_comments(norm_src), re.S)
    if not blk:
        raise SystemExit('Canon unique[] table not found')
    rows = re.findall(r'\{\s*(CanonID_\w+)\s*,\s*"([^"]*)"\s*\}', blk.group(1))
    if len(rows) != len(re.findall(r'\{\s*CanonID_', blk.group(1))) or not rows:
        raise SystemExit('could not parse the Canon unique[] table')
    return [(ids[n], model) for n, model in rows], ids['CanonID_EOS_D2000C'], ids['CanonID_EOS_D6000C']


def kt_str(s):
    out = []
    for ch in s:
        if ch == '\\':
            out.append('\\\\')
        elif ch == '"':
            out.append('\\"')
        elif ch == '$':
            out.append('\\$')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\t':
            out.append('\\t')
        elif ord(ch) < 32 or ord(ch) > 126:
            out.append('\\u%04x' % ord(ch))
        else:
            out.append(ch)
    return '"' + ''.join(out) + '"'


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    root = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        here, '..', 'engine', 'src', 'main', 'kotlin', 'app', 'spektralab', 'raw', 'CameraMatrices.kt')

    ver = read(os.path.join(root, 'libraw', 'libraw_version.h'))
    maj, mnr, pat = (re.search(r'LIBRAW_%s_VERSION\s+(\d+)' % k, ver).group(1)
                     for k in ('MAJOR', 'MINOR', 'PATCH'))
    version = '%s.%s.%s' % (maj, mnr, pat)

    corp = parse_corp_table(read(os.path.join(root, 'src', 'metadata', 'identify.cpp')))
    first_name = {}
    for enum, name in corp:
        first_name.setdefault(enum, name)

    rows = parse_color_table(read(os.path.join(root, 'src', 'tables', 'colordata.cpp')))
    norm_src = read(os.path.join(root, 'src', 'metadata', 'normalize_model.cpp'))
    aliases = parse_alias_tables(norm_src)
    canon_ids, d2000c, d6000c = parse_canon_ids(norm_src, read(os.path.join(root, 'internal', 'libraw_cameraids.h')))
    wanted = ['fujialias', 'kodakalias', 'leafalias', 'KonicaMinolta_aliases', 'nikonalias', 'olyalias',
              'panalias', 'phase1alias', 'SamsungPentax_aliases', 'samsungalias']
    for w in wanted:
        if w not in aliases or not aliases[w]:
            raise SystemExit('alias table %s not found' % w)

    # ---- table chunks ------------------------------------------------------------------------
    lines = []
    for enum, prefix, black, white, vals in rows:
        if enum not in first_name:
            raise SystemExit('maker %s missing from CorpTable' % enum)
        if '\t' in prefix or '\n' in prefix:
            raise SystemExit('unsupported character in prefix %r' % prefix)
        lines.append('\t'.join([first_name[enum], prefix, str(black), str(white),
                                ','.join(str(v) for v in vals)]))
    chunks = [lines[i:i + CHUNK_ENTRIES] for i in range(0, len(lines), CHUNK_ENTRIES)]
    chunk_lits = []
    for ch in chunks:
        lit = kt_str('\n'.join(ch))
        if len(lit.encode('utf-8')) > MAX_CONST_BYTES:
            raise SystemExit('chunk too large, lower CHUNK_ENTRIES')
        chunk_lits.append(lit)

    kt = []
    w = kt.append
    w('// GENERATED FILE - DO NOT EDIT.  Regenerate with:  python3 tools/gen_camera_matrices.py <LibRaw dir>\n')
    w('//\n')
    w('// Source: LibRaw %s  (src/tables/colordata.cpp `adobe_coeff`, src/metadata/identify.cpp\n' % version)
    w('// `CorpTable` / `simplify_make_model`, src/metadata/normalize_model.cpp `GetNormalizedModel`).\n')
    w('// LibRaw is Copyright 2008-2025 LibRaw LLC, licensed under LGPL 2.1 or CDDL 1.0 (your choice) and derives\n')
    w('// from dcraw.c by Dave Coffin.  The matrices are mostly from Adobe DNG Converter ("All matrices are from\n')
    w('// Adobe DNG Converter unless otherwise noted" in colordata.cpp).  This project is GPLv3; the LGPL 2.1\n')
    w('// licence of LibRaw permits redistribution under the GPL.  See https://www.libraw.org/\n')
    w('package app.spektralab.raw\n\n')
    w('/**\n')
    w(' * One row of the LibRaw/dcraw per-camera colour table.\n')
    w(' *\n')
    w(' * @property maker canonical LibRaw maker name ("Canon", "Nikon", "Sony", "Phase One", ...).\n')
    w(' * @property modelPrefix model prefix this row applies to (case-insensitive, empty = every model of the maker).\n')
    w(' * @property black LibRaw `t_black` verbatim: > 0 means "force this black level", < 0 means "use -black only\n')
    w(' *   when the file declares no black level of its own", 0 means "not specified".  See [blackLevel] and\n')
    w(' *   [blackIsFallback].\n')
    w(' * @property white LibRaw `t_maximum` (saturation level), 0 = not specified.\n')
    w(' * @property colors number of colour channels the matrix is for: 3 (RGB) or 4 (e.g. CMYG / RGBE sensors),\n')
    w(' *   or 0 when the row carries no matrix (black / white only).\n')
    w(' * @property xyzToCamera the matrix mapping CIE XYZ (D65) to camera RGB(G), as in DNG `ColorMatrix`, indexed\n')
    w(' *   `[row][col]` with `colors` rows of 3 columns (X, Y, Z).  Rows 0..2 are the first three camera channels;\n')
    w(' *   for 4-colour sensors row 3 is the fourth channel.  `null` when [colors] == 0.\n')
    w(' */\n')
    w('class CameraMatrixEntry(\n')
    w('    val maker: String,\n')
    w('    val modelPrefix: String,\n')
    w('    val black: Int,\n')
    w('    val white: Int,\n')
    w('    val colors: Int,\n')
    w('    val xyzToCamera: Array<DoubleArray>?,\n')
    w(') {\n')
    w('    /** Absolute black level, 0 if unspecified. */\n')
    w('    val blackLevel: Int get() = if (black < 0) -black else black\n\n')
    w('    /** True when [blackLevel] must only be used if the file itself gives no black level. */\n')
    w('    val blackIsFallback: Boolean get() = black < 0\n\n')
    w('    override fun toString(): String = "CameraMatrixEntry($maker, \\"$modelPrefix\\", black=$black, white=$white, colors=$colors)"\n')
    w('}\n\n')
    w('/**\n')
    w(' * Lookup of per-camera colour data for raw formats that do not carry a colour matrix (CR2, NEF, ARW, ...).\n')
    w(' * Mirrors LibRaw\'s `adobe_coeff()` including the make / model normalisation LibRaw performs first.\n')
    w(' * Table: %d entries from LibRaw %s.\n' % (len(rows), version))
    w(' */\n')
    w('object CameraMatrices {\n')
    w('    /** LibRaw release the table was generated from. */\n')
    w('    const val SOURCE_VERSION: String = "LibRaw %s"\n\n' % version)
    w('    /**\n')
    w('     * Find the colour data for a camera given the raw `Make` and `Model` strings (as found in TIFF/EXIF tags).\n')
    w('     * Applies [normalizeMakeModel] then LibRaw\'s matching: the first table row (in table order, which lists\n')
    w('     * specific models before general ones) whose maker equals the normalised maker and whose prefix is a\n')
    w('     * case-insensitive prefix of the normalised model.  Returns null for unknown cameras.\n')
    w('     */\n')
    w('    fun find(make: String, model: String): CameraMatrixEntry? {\n')
    w('        val n = normalizeMakeModel(make, model)\n')
    w('        return findNormalized(n.maker, n.model)\n')
    w('    }\n\n')
    w('    /**\n')
    w('     * [find] for a Canon body: LibRaw names Canon cameras by the model id in the maker note (tag 0x0010), so\n')
    w('     * regional names ("EOS Rebel T2i", "EOS Kiss X4") and any Model string find the row of the camera\n')
    w('     * ("EOS 550D").  Falls back to [find] when the id is 0 or unknown.\n')
    w('     */\n')
    w('    fun findCanon(uniqueId: Long, make: String, model: String): CameraMatrixEntry? {\n')
    w('        val n = normalizeMakeModel(make, model)\n')
    w('        val byId = canonModelName(uniqueId)\n')
    w('        if (byId != null && n.model != "EOS Ra") findNormalized("Canon", byId)?.let { return it }\n')
    w('        return findNormalized(n.maker, n.model)\n')
    w('    }\n\n')
    w('    /** LibRaw GetNormalizedModel `unique[]`: the model name for a Canon maker-note model id, null if unknown. */\n')
    w('    fun canonModelName(uniqueId: Long): String? =\n')
    w('        if (uniqueId == 0L || uniqueId == 0x%xL || uniqueId == 0x%xL) null else CANON_MODEL_BY_ID[uniqueId]\n\n' % (d2000c, d6000c))
    w('    /** Lookup with an already normalised canonical maker name and model (no normalisation is applied). */\n')
    w('    fun findNormalized(maker: String?, model: String): CameraMatrixEntry? {\n')
    w('        if (maker == null) return null\n')
    w('        val list = byMaker[maker] ?: return null\n')
    w('        for (e in list) {\n')
    w('            if (e.modelPrefix.isEmpty() || startsWithIgnoreAscii(model, e.modelPrefix)) return e\n')
    w('        }\n')
    w('        return null\n')
    w('    }\n\n')
    w('    /** Number of rows in the table. */\n')
    w('    val size: Int get() = entries.size\n\n')
    w('    /** All rows in LibRaw table order. */\n')
    w('    val all: List<CameraMatrixEntry> get() = entries\n\n')
    w('    /** Result of [normalizeMakeModel]; [maker] is the canonical LibRaw maker name or null if the maker is unknown. */\n')
    w('    class Normalized(val maker: String?, val make: String, val model: String)\n\n')
    w('    /**\n')
    w('     * Reproduces the string-only part of LibRaw\'s identification: `simplify_make_model()` (maker detection\n')
    w('     * by case-insensitive substring against the maker list, removal of the maker from the model, FinePix /\n')
    w('     * "Digital Camera" prefixes, ...) followed by the alias handling of `GetNormalizedModel()` (e.g. Nikon\n')
    w('     * "E990" -> "COOLPIX 990", Panasonic/Leica badge twins, Phase One / Mamiya / Leaf back aliases).\n')
    w('     * Parts of GetNormalizedModel that need information from inside the file (Canon / Sony / Olympus\n')
    w('     * maker-note camera ids, Hasselblad body parsing, Ricoh GXR lens modules) are not reproduced.\n')
    w('     */\n')
    w('    fun normalizeMakeModel(rawMake: String, rawModel: String): Normalized {\n')
    w('        var make = cString(rawMake)\n')
    w('        var model = cString(rawModel)\n')
    w('        // --- simplify_make_model ---------------------------------------------------------------\n')
    w('        var maker: String? = null\n')
    w('        for (name in CORP_NAMES) {\n')
    w('            if (containsIgnoreAscii(make, name)) { maker = firstCorpName(name); break }\n')
    w('        }\n')
    w('        if (maker == "HMD Global" && startsWithIgnoreAscii(model, "Nokia")) maker = "Nokia"\n')
    w('        else if (maker == "JK Imaging" && startsWithIgnoreAscii(model, "Kodak")) maker = "Kodak"\n')
    w('        else if (maker == "Ricoh" && startsWithIgnoreAscii(model, "PENTAX")) maker = "Pentax"\n')
    w('        if (maker != null) make = maker\n')
    w('        if (maker == "Kodak" || maker == "Leica") {\n')
    w('            var cut = indexOfIgnoreAscii(model, " DIGITAL CAMERA")\n')
    w('            if (cut < 0) cut = model.indexOf("FILE VERSION")\n')
    w('            if (cut >= 0) model = model.substring(0, cut)\n')
    w('        }\n')
    w('        make = make.trimEnd { isAsciiSpace(it) }\n')
    w('        model = model.trimEnd { isAsciiSpace(it) }\n')
    w('        if (startsWithIgnoreAscii(model, make) && model.length > make.length && model[make.length] == \' \') {\n')
    w('            model = model.substring(make.length + 1)\n')
    w('        }\n')
    w('        if (maker == "Fujifilm" && model.startsWith("FinePix")) {\n')
    w('            model = model.substring(7)\n')
    w('            if (model.startsWith(" ")) model = model.substring(1)\n')
    w('        } else if ((maker == "Kodak" || maker == "Konica") && model.startsWith("Digital Camera ")) {\n')
    w('            model = model.substring(15)\n')
    w('        }\n\n')
    w('        // --- GetNormalizedModel (string-only part) -----------------------------------------------\n')
    w('        if (maker == "OM Digital") maker = "Olympus"\n')
    w('        else if (maker == "Canon" && (model.startsWith("EOS D2000") || model.startsWith("EOS D6000") ||\n')
    w('                model.startsWith("EOSDCS"))) maker = "Kodak"\n')
    w('        else if (maker == "Photo Control" && startsWithIgnoreAscii(model, "Camerz ZDS 14")) maker = "Kodak"\n')
    w('        if (maker == "Kodak") {\n')
    w('            if (charAt0(model, 6) == \' \' && (model.startsWith("DCS4") || model.startsWith("NC2000"))) model = model.substring(0, 6)\n')
    w('            val c6 = charAt0(model, 6)\n')
    w('            if (c6 != \'A\' && c6 != \'I\' && c6 != \'M\' && model.startsWith("NC2000")) model = model.substring(0, 6)\n')
    w('        }\n')
    w('        var norm = model\n')
    w('        when (maker) {\n')
    w('            "Fujifilm" -> norm = aliasOf(FUJI_ALIAS, model, ignoreCase = false)?.orig ?: norm\n')
    w('            "Mamiya" -> {\n')
    w('                aliasOf(PHASE1_ALIAS, model, ignoreCase = false, checkOrig = false)?.let { if (it.viaAlias) { maker = "Phase One"; norm = it.orig } }\n')
    w('                aliasOf(LEAF_ALIAS, model, ignoreCase = false, checkOrig = false)?.let { if (it.viaAlias) { maker = "Leaf"; norm = it.orig } }\n')
    w('            }\n')
    w('            "Leaf" -> {\n')
    w('                aliasOf(LEAF_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }\n')
    w('                val p = norm.indexOf(\'(\')\n')
    w('                if (p >= 0) norm = norm.substring(0, p)\n')
    w('            }\n')
    w('            "Minolta", "Konica" -> {\n')
    w('                if (maker == "Konica" && startsWithIgnoreAscii(model, "DiMAGE")) {\n')
    w('                    maker = "Minolta"; make = "Minolta"\n')
    w('                } else {\n')
    w('                    val a = aliasOf(KONICA_MINOLTA_ALIAS, model, ignoreCase = true, aliasIgnoreCase = true)\n')
    w('                    if (a != null) { maker = "Minolta"; make = "Minolta"; if (a.viaAlias) norm = a.orig }\n')
    w('                }\n')
    w('            }\n')
    w('            "Nikon" -> norm = aliasOf(NIKON_ALIAS, model, ignoreCase = false)?.orig ?: norm\n')
    w('            "Olympus" -> norm = aliasOf(OLYMPUS_ALIAS, model, ignoreCase = false)?.orig ?: norm\n')
    w('            "Panasonic", "Leica", "Yuneec" -> aliasOf(PANASONIC_ALIAS, model, ignoreCase = false)?.let {\n')
    w('                if (it.viaAlias) { maker = "Panasonic"; norm = it.orig }\n')
    w('            }\n')
    w('            "Pentax" -> {\n')
    w('                aliasOf(PENTAX_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }\n')
    w('                if (model.startsWith("GR")) { maker = "Ricoh"; make = "Ricoh" }\n')
    w('            }\n')
    w('            "Phase One" -> aliasOf(PHASE1_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }\n')
    w('            "Samsung" -> {\n')
    w('                val wb = when {\n')
    w('                    model.contains("WB5500") || model.contains("HZ50W") -> "WB5500"\n')
    w('                    model.contains("WB5000") || model.contains("HZ25W") -> "WB5000"\n')
    w('                    model.contains("WB550") || model.contains("HZ15W") -> "WB550"\n')
    w('                    model.contains("WB500") || model.contains("HZ10W") -> "WB500"\n')
    w('                    else -> null\n')
    w('                }\n')
    w('                if (wb != null) { model = wb; norm = wb }\n')
    w('                else aliasOf(SAMSUNG_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }\n')
    w('            }\n')
    w('            "Kodak" -> {\n')
    w('                norm = removeSubstrIgnoreAscii(norm, "EasyShare")\n')
    w('                norm = removeSubstrIgnoreAscii(norm, "ZOOM")\n')
    w('                norm = collapseSpaces(norm)\n')
    w('                aliasOf(KODAK_ALIAS, model, ignoreCase = false)?.let { if (it.viaAlias) norm = it.orig }\n')
    w('                when {\n')
    w('                    model.contains("DC25") -> { model = "DC25"; norm = model }\n')
    w('                    model == "40" -> { model = "DC40"; norm = model }\n')
    w('                    model.contains("DC50") -> { model = "DC50"; norm = model }\n')
    w('                    model.contains("DC120") -> { model = "DC120"; norm = model }\n')
    w('                }\n')
    w('            }\n')
    w('        }\n')
    w('        return Normalized(maker, make, norm)\n')
    w('    }\n\n')

    # ---- private helpers ----
    w('    // ------------------------------------------------------------------------------------------\n')
    w('    // implementation\n\n')
    w('    private class AliasHit(val orig: String, val viaAlias: Boolean)\n\n')
    w('    /**\n')
    w('     * Walks a LibRaw alias table: entries starting with \'@\' name a canonical model, the entries after it are\n')
    w('     * its aliases.  Returns null when no alias matches (also when [model] equals a canonical name and\n')
    w('     * [checkOrig] is set, which stops the scan exactly like LibRaw\'s `break`), or a hit otherwise.\n')
    w('     * For Konica/Minolta a hit on the canonical name itself is reported with viaAlias = false.\n')
    w('     */\n')
    w('    private fun aliasOf(\n')
    w('        table: Array<String>, model: String, ignoreCase: Boolean,\n')
    w('        checkOrig: Boolean = true, aliasIgnoreCase: Boolean = false,\n')
    w('    ): AliasHit? {\n')
    w('        var orig = ""\n')
    w('        for (s in table) {\n')
    w('            if (s.startsWith("@")) {\n')
    w('                orig = s.substring(1)\n')
    w('                if (checkOrig && model == orig) return if (ignoreCase) AliasHit(orig, false) else null\n')
    w('            } else if (if (aliasIgnoreCase) equalsIgnoreAscii(model, s) else model == s) {\n')
    w('                return AliasHit(orig, true)\n')
    w('            }\n')
    w('        }\n')
    w('        return null\n')
    w('    }\n\n')
    w('    private fun isAsciiSpace(c: Char) = c == \' \' || c in \'\\t\'..\'\\r\'\n\n')
    w('    private fun charAt0(s: String, i: Int): Char = if (i < s.length) s[i] else \'\\u0000\'\n\n')
    w('    /** C strings stop at the first NUL. */\n')
    w('    private fun cString(s: String): String { val z = s.indexOf(\'\\u0000\'); return if (z >= 0) s.substring(0, z) else s }\n\n')
    w('    private fun lowerAscii(c: Char): Char = if (c in \'A\'..\'Z\') (c.code + 32).toChar() else c\n\n')
    w('    private fun regionEqualsIgnoreAscii(s: String, at: Int, p: String): Boolean {\n')
    w('        if (at < 0 || at + p.length > s.length) return false\n')
    w('        for (i in p.indices) if (lowerAscii(s[at + i]) != lowerAscii(p[i])) return false\n')
    w('        return true\n')
    w('    }\n\n')
    w('    /** strncasecmp(s, prefix, prefix.length) == 0 */\n')
    w('    internal fun startsWithIgnoreAscii(s: String, prefix: String): Boolean = regionEqualsIgnoreAscii(s, 0, prefix)\n\n')
    w('    private fun equalsIgnoreAscii(a: String, b: String): Boolean = a.length == b.length && regionEqualsIgnoreAscii(a, 0, b)\n\n')
    w('    /** strcasestr */\n')
    w('    private fun indexOfIgnoreAscii(s: String, p: String): Int {\n')
    w('        for (i in 0..s.length - p.length) if (regionEqualsIgnoreAscii(s, i, p)) return i\n')
    w('        return -1\n')
    w('    }\n\n')
    w('    private fun containsIgnoreAscii(s: String, p: String) = indexOfIgnoreAscii(s, p) >= 0\n\n')
    w('    /** LibRaw remove_caseSubstr(): blank out every occurrence, then trim. */\n')
    w('    private fun removeSubstrIgnoreAscii(s: String, p: String): String {\n')
    w('        val sb = StringBuilder(s)\n')
    w('        var at = indexOfIgnoreAscii(sb.toString(), p)\n')
    w('        while (at >= 0) {\n')
    w('            for (i in at until at + p.length) sb.setCharAt(i, \' \')\n')
    w('            at = indexOfIgnoreAscii(sb.toString(), p)\n')
    w('        }\n')
    w('        return sb.toString().trim { isAsciiSpace(it) }\n')
    w('    }\n\n')
    w('    /** LibRaw removeExcessiveSpaces(): drop leading spaces, collapse runs of spaces, drop one trailing space. */\n')
    w('    private fun collapseSpaces(s: String): String {\n')
    w('        val sb = StringBuilder()\n')
    w('        var prevSpace = false\n')
    w('        var started = false\n')
    w('        for (c in s) {\n')
    w('            if (c == \' \') {\n')
    w('                if (started && !prevSpace) { sb.append(c); prevSpace = true }\n')
    w('            } else { sb.append(c); prevSpace = false; started = true }\n')
    w('        }\n')
    w('        if (sb.isNotEmpty() && sb[sb.length - 1] == \' \') sb.setLength(sb.length - 1)\n')
    w('        return sb.toString()\n')
    w('    }\n\n')
    w('    private fun firstCorpName(name: String): String = CORP_FIRST_NAME[name] ?: name\n\n')

    # ---- data ----
    w('    // ---- data extracted from LibRaw %s ------------------------------------------------------\n\n' % version)
    w('    /** LibRaw `CorpTable` names in match order (maker detection is a case-insensitive substring test). */\n')
    w('    private val CORP_NAMES: Array<String> = arrayOf(\n')
    for _, name in corp:
        w('        %s,\n' % kt_str(name))
    w('    )\n\n')
    w('    /** Several CorpTable names map to one maker id ("Phase One" / "PhaseOne"); the first one is canonical. */\n')
    w('    private val CORP_FIRST_NAME: Map<String, String> = HashMap<String, String>().also { m ->\n')
    for enum, name in corp:
        pass
    ids = {}
    for enum, name in corp:
        ids.setdefault(enum, []).append(name)
    for enum, names in ids.items():
        for extra in names[1:]:
            w('        m[%s] = %s\n' % (kt_str(extra), kt_str(names[0])))
    w('    }\n\n')
    for const, key in (('FUJI_ALIAS', 'fujialias'), ('KODAK_ALIAS', 'kodakalias'), ('LEAF_ALIAS', 'leafalias'),
                       ('KONICA_MINOLTA_ALIAS', 'KonicaMinolta_aliases'), ('NIKON_ALIAS', 'nikonalias'),
                       ('OLYMPUS_ALIAS', 'olyalias'), ('PANASONIC_ALIAS', 'panalias'),
                       ('PHASE1_ALIAS', 'phase1alias'), ('PENTAX_ALIAS', 'SamsungPentax_aliases'),
                       ('SAMSUNG_ALIAS', 'samsungalias')):
        items = aliases[key]
        w('    private val %s: Array<String> = arrayOf(\n' % const)
        for it in items:
            w('        %s,\n' % kt_str(it))
        w('    )\n\n')
    w('    /** Canon maker-note model id -> LibRaw model name (GetNormalizedModel `unique[]`, %d entries). */\n' % len(canon_ids))
    w('    private val CANON_MODEL_BY_ID: Map<Long, String> = mapOf(\n')
    for cid, name in canon_ids:
        w('        0x%08xL to %s,\n' % (cid, kt_str(name)))
    w('    )\n\n')
    w('    // Table rows are packed into string constants (one row per line, tab separated:\n')
    w('    // maker, model prefix, black, white, 12 matrix values x10000 row-major, 4th row all zero for RGB)\n')
    w('    // so that no single method or constant approaches the JVM 64 KB limits.\n')
    w('    private val CHUNKS: Array<String> = arrayOf(\n')
    for lit in chunk_lits:
        w('        %s,\n' % lit)
    w('    )\n\n')
    w('    private val entries: List<CameraMatrixEntry> by lazy { parse() }\n\n')
    w('    private val byMaker: Map<String, List<CameraMatrixEntry>> by lazy {\n')
    w('        val m = LinkedHashMap<String, MutableList<CameraMatrixEntry>>()\n')
    w('        for (e in entries) m.getOrPut(e.maker) { ArrayList() }.add(e)\n')
    w('        m\n')
    w('    }\n\n')
    w('    private fun parse(): List<CameraMatrixEntry> {\n')
    w('        val out = ArrayList<CameraMatrixEntry>(%d)\n' % len(rows))
    w('        for (chunk in CHUNKS) {\n')
    w('            for (line in chunk.split(\'\\n\')) {\n')
    w('                if (line.isEmpty()) continue\n')
    w('                val f = line.split(\'\\t\')\n')
    w('                val v = f[4].split(\',\').map { it.toInt() }\n')
    w('                val colors = if (v[0] == 0) 0 else if (v[9] != 0 || v[10] != 0 || v[11] != 0) 4 else 3\n')
    w('                val m = if (colors == 0) null else Array(colors) { r -> DoubleArray(3) { c -> v[r * 3 + c] / 10000.0 } }\n')
    w('                out.add(CameraMatrixEntry(f[0], f[1], f[2].toInt(), f[3].toInt(), colors, m))\n')
    w('            }\n')
    w('        }\n')
    w('        return out\n')
    w('    }\n')
    w('}\n')

    text = ''.join(kt)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('LibRaw %s: %d table rows (%d with matrix, %d with 4th row), %d chunks, wrote %s'
          % (version, len(rows), sum(1 for r in rows if r[4][0]), sum(1 for r in rows if any(r[4][9:])),
             len(chunks), out_path))


if __name__ == '__main__':
    main()
