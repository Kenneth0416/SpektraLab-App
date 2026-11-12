package app.spektralab.engine

import kotlin.math.max
import kotlin.math.pow

/**
 * User-facing "film x paper" settings shared by photos and videos.
 * Mirrors the knobs of the interaction prototype; [LookMapper] turns it into
 * spektrafilm runtime params.
 */
data class Look(
    val film: String = "kodak_portra_400",
    /** "auto", "none" (scan the film directly) or a print stock id. */
    val paper: String = "auto",
    /** Print exposure, EV. */
    val printEv: Double = 0.0,
    /** Print glare amount, 0..1. */
    val glare: Double = 0.0,
    /** Film exposure, EV (print exposure compensation keeps brightness). */
    val filmEv: Double = 0.0,
    /** Development: -1 pull, 0 normal, +1/+2 push. */
    val push: Int = 0,
    val halation: Double = 1.0,
    val halationSize: Double = 1.0,
    val grain: Double = 1.0,
    val grainSize: Double = 1.0,
    /** "off" or a diffusion filter family. */
    val diffusion: String = "off",
    val diffusionAmount: Double = 0.5,
    /** Enlarger filter shifts, Kodak CC. */
    val yFilter: Double = 0.0,
    val mFilter: Double = 0.0,
    val format: String = "135",
    /** "none", "white" (spektrafilm print white border) or "edge" (film rebate with edge print). */
    val border: String = "none",
    val dateStamp: Boolean = false,
) {
    val filmEdge: Boolean get() = border == "edge"
    val whiteBorder: Boolean get() = border == "white"

    companion object {
        val DEFAULT = Look()
    }
}

data class FilmInfo(val stock: String, val name: String, val iso: String, val kind: String, val accent: Long)
data class PaperInfo(val id: String, val label: String)
data class DiffusionInfo(val id: String, val label: String)
data class BorderInfo(val id: String)
data class FormatInfo(val id: String, val label: String, val longMm: Double, val aspect: Double)

/** Stocks shipped with spektrafilm, with UI labels. */
object Catalog {
    val films = listOf(
        FilmInfo("kodak_portra_400", "Portra 400", "400", "negative", 0xFFE9C48A),
        FilmInfo("kodak_portra_160", "Portra 160", "160", "negative", 0xFFEED3A6),
        FilmInfo("kodak_portra_800", "Portra 800", "800", "negative", 0xFFE3A86E),
        FilmInfo("kodak_ektar_100", "Ektar 100", "100", "negative", 0xFFF0624A),
        FilmInfo("kodak_gold_200", "Gold 200", "200", "negative", 0xFFF2B63A),
        FilmInfo("kodak_ultramax_400", "UltraMax 400", "400", "negative", 0xFF5CB4EA),
        FilmInfo("fujifilm_c200", "Fujicolor C200", "200", "negative", 0xFF6BC48A),
        FilmInfo("fujifilm_pro_400h", "Pro 400H", "400", "negative", 0xFF8FD3C1),
        FilmInfo("fujifilm_xtra_400", "X-TRA 400", "400", "negative", 0xFF4FB58C),
        FilmInfo("kodak_vision3_50d", "Vision3 50D", "50D", "cine", 0xFFB9C7E8),
        FilmInfo("kodak_vision3_250d", "Vision3 250D", "250D", "cine", 0xFF9DB4E4),
        FilmInfo("kodak_vision3_200t", "Vision3 200T", "200T", "cine", 0xFF8AA8E0),
        FilmInfo("kodak_vision3_500t", "Vision3 500T", "500T", "cine", 0xFF7FA6E6),
        FilmInfo("kodak_verita_200d", "Verita 200D", "200D", "cine", 0xFFA7B0D8),
        FilmInfo("fujifilm_velvia_100", "Velvia 100", "100", "positive", 0xFFE0457B),
        FilmInfo("fujifilm_provia_100f", "Provia 100F", "100", "positive", 0xFF5FA8D3),
        FilmInfo("kodak_ektachrome_100", "Ektachrome 100", "100", "positive", 0xFF4F8FD8),
        FilmInfo("kodak_kodachrome_64", "Kodachrome 64", "64", "positive", 0xFFD9573B),
    )

    val papers = listOf(
        PaperInfo("auto", "Auto"),
        PaperInfo("kodak_portra_endura", "Portra Endura"),
        PaperInfo("kodak_supra_endura", "Supra Endura"),
        PaperInfo("kodak_ultra_endura", "Ultra Endura"),
        PaperInfo("kodak_endura_premier", "Endura Premier"),
        PaperInfo("kodak_ektacolor_edge", "Ektacolor Edge"),
        PaperInfo("fujifilm_crystal_archive_typeii", "Crystal Archive"),
        PaperInfo("kodak_2383", "2383"),
        PaperInfo("kodak_2393", "2393"),
        PaperInfo("none", "No print"),
    )

    val diffusions = listOf(
        DiffusionInfo("off", "Off"),
        DiffusionInfo("black_pro_mist", "Black Pro-Mist"),
        DiffusionInfo("pro_mist", "Pro-Mist"),
        DiffusionInfo("glimmerglass", "Glimmerglass"),
        DiffusionInfo("cinebloom", "CineBloom"),
    )

    val formats = listOf(
        FormatInfo("half", "Half frame", 24.0, 4.0 / 3.0),
        FormatInfo("135", "135", 36.0, 1.5),
        FormatInfo("xpan", "XPan", 65.0, 65.0 / 24.0),
        FormatInfo("66", "6×6", 56.0, 1.0),
        FormatInfo("67", "6×7", 70.0, 70.0 / 56.0),
    )

    val borders = listOf(BorderInfo("none"), BorderInfo("white"), BorderInfo("edge"))

    fun border(id: String): BorderInfo = borders.firstOrNull { it.id == id } ?: borders.first()

    fun film(stock: String): FilmInfo = films.firstOrNull { it.stock == stock } ?: films.first()
    fun paper(id: String): PaperInfo = papers.firstOrNull { it.id == id } ?: papers.first()
    fun diffusion(id: String): DiffusionInfo = diffusions.firstOrNull { it.id == id } ?: diffusions.first()
    fun format(id: String): FormatInfo = formats.firstOrNull { it.id == id } ?: formats[1]
}

/** Loads baked stocks (cached). [open] maps an asset path like "film/x.spk" to a stream. */
class EngineData(private val open: (String) -> java.io.InputStream) {
    val global: GlobalData by lazy { open("global.spk").use { GlobalData.read(it) } }
    private val films = HashMap<String, FilmProfile>()
    private val papers = HashMap<String, PaperProfile>()

    @Synchronized fun film(stock: String): FilmProfile =
        films.getOrPut(stock) { open("film/$stock.spk").use { FilmProfile.read(it) } }

    @Synchronized fun paper(stock: String): PaperProfile =
        papers.getOrPut(stock) { open("paper/$stock.spk").use { PaperProfile.read(it) } }
}

object LookMapper {
    /** Resolve the print stock for a look ("" = scan the film directly). */
    fun resolvePaper(look: Look, data: EngineData): String {
        val film = data.film(filmStock(look))
        if (film.isPositive || look.paper == "none") return ""
        if (look.paper == "auto") return film.targetPrint.ifEmpty { "kodak_portra_endura" }
        return look.paper
    }

    /** Portra 800 pushes use spektrafilm's measured push-processed profiles. */
    fun filmStock(look: Look): String = when {
        look.film == "kodak_portra_800" && look.push == 1 -> "kodak_portra_800_push1"
        look.film == "kodak_portra_800" && look.push == 2 -> "kodak_portra_800_push2"
        else -> look.film
    }

    fun toRuntime(look: Look, data: EngineData): RuntimeParams {
        val stock = filmStock(look)
        val film = data.film(stock)
        val paperId = resolvePaper(look, data)
        val paper = if (paperId.isEmpty()) null else data.paper(paperId)
        val p = RuntimeParams.digest(film, paper, data.global, scanFilm = paperId.isEmpty())
        val measuredPush = stock != look.film
        // push/pull: under/over-expose by N stops, develop to higher/lower contrast
        p.exposureCompensationEv = look.filmEv - look.push
        p.densityCurveGamma = if (measuredPush) 1.0 else 1.0 + 0.15 * look.push
        p.enlarger.printExposure = 2.0.pow(look.printEv)
        p.enlarger.yFilterShift = look.yFilter
        p.enlarger.mFilterShift = look.mFilter
        p.glarePercent = 0.03 + 3.0 * look.glare
        p.halation.halationAmount = max(look.halation, 0.0)
        p.halation.halationSpatialScale = max(look.halationSize, 0.05)
        p.grain.amount = max(look.grain, 0.0)
        p.grain.active = look.grain > 0.0
        p.grain.particleAreaUm2 = 0.2 * look.grainSize * look.grainSize
        if (look.diffusion != "off" && look.diffusion in DiffusionFilters.names && look.diffusionAmount > 0) {
            p.diffusion.active = true
            p.diffusion.family = look.diffusion
            p.diffusion.strength = 2.0 * look.diffusionAmount
        }
        p.filmFormatMm = Catalog.format(look.format).longMm
        return p
    }
}
