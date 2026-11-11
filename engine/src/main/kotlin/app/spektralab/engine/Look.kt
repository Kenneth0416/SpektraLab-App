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

data class FilmInfo(val stock: String, val name: String, val iso: String, val kind: String, val note: String,
                    val accent: Long)
data class PaperInfo(val id: String, val label: String, val note: String)
data class DiffusionInfo(val id: String, val label: String, val note: String)
data class BorderInfo(val id: String, val label: String, val note: String)
data class FormatInfo(val id: String, val label: String, val longMm: Double, val aspect: Double, val note: String)

/** Stocks shipped with spektrafilm, with UI labels. */
object Catalog {
    val films = listOf(
        FilmInfo("kodak_portra_400", "Portra 400", "400", "彩色负片", "肤色柔和，宽容度高，过曝也好看。", 0xFFE9C48A),
        FilmInfo("kodak_portra_160", "Portra 160", "160", "彩色负片", "更细腻、更低反差的 Portra，适合棚拍和人像。", 0xFFEED3A6),
        FilmInfo("kodak_portra_800", "Portra 800", "800", "彩色负片", "高感 Portra，暗部有层次，迫冲时使用真实的迫冲曲线。", 0xFFE3A86E),
        FilmInfo("kodak_ektar_100", "Ektar 100", "100", "彩色负片", "颗粒极细，颜色浓、反差高，适合风光。", 0xFFF0624A),
        FilmInfo("kodak_gold_200", "Gold 200", "200", "彩色负片", "暖黄怀旧，晴天最合适。", 0xFFF2B63A),
        FilmInfo("kodak_ultramax_400", "UltraMax 400", "400", "彩色负片", "日常卷，饱和偏高，颗粒比 Portra 明显。", 0xFF5CB4EA),
        FilmInfo("fujifilm_c200", "Fujicolor C200", "200", "彩色负片", "清爽偏绿的日常卷。", 0xFF6BC48A),
        FilmInfo("fujifilm_pro_400h", "Pro 400H", "400", "彩色负片", "通透的青绿色调，高光柔和。", 0xFF8FD3C1),
        FilmInfo("fujifilm_xtra_400", "X-TRA 400", "400", "彩色负片", "饱和、反差略高的富士日常卷。", 0xFF4FB58C),
        FilmInfo("kodak_vision3_50d", "Vision3 50D", "50D", "电影负片", "日光型电影卷，最细的颗粒。", 0xFFB9C7E8),
        FilmInfo("kodak_vision3_250d", "Vision3 250D", "250D", "电影负片", "日光型电影卷，宽容度极高。", 0xFF9DB4E4),
        FilmInfo("kodak_vision3_200t", "Vision3 200T", "200T", "电影负片", "灯光型电影卷，日光下偏蓝。", 0xFF8AA8E0),
        FilmInfo("kodak_vision3_500t", "Vision3 500T", "500T", "电影负片", "灯光型电影卷，夜景和霓虹的首选，日光下偏蓝。", 0xFF7FA6E6),
        FilmInfo("kodak_verita_200d", "Verita 200D", "200D", "电影负片", "日光型电影负片。", 0xFFA7B0D8),
        FilmInfo("fujifilm_velvia_100", "Velvia 100", "100", "彩色反转片", "极高饱和与反差，风光经典。直接扫描，不印相。", 0xFFE0457B),
        FilmInfo("fujifilm_provia_100f", "Provia 100F", "100", "彩色反转片", "准确中性的反转片。直接扫描，不印相。", 0xFF5FA8D3),
        FilmInfo("kodak_ektachrome_100", "Ektachrome 100", "100", "彩色反转片", "干净偏冷的反转片。直接扫描，不印相。", 0xFF4F8FD8),
        FilmInfo("kodak_kodachrome_64", "Kodachrome 64", "64", "彩色反转片", "浓郁的红与深沉的影调。直接扫描，不印相。", 0xFFD9573B),
    )

    val papers = listOf(
        PaperInfo("auto", "自动", "跟随胶片的原配相纸；反转片直接扫描。"),
        PaperInfo("kodak_portra_endura", "Portra Endura", "彩色相纸：照片感，反差适中。"),
        PaperInfo("kodak_supra_endura", "Supra Endura", "彩色相纸：饱和度和反差略高。"),
        PaperInfo("kodak_ultra_endura", "Ultra Endura", "彩色相纸：最鲜艳的 Endura。"),
        PaperInfo("kodak_endura_premier", "Endura Premier", "彩色相纸：新一代，黑位更深。"),
        PaperInfo("kodak_ektacolor_edge", "Ektacolor Edge", "彩色相纸：冲印店常用的经济型相纸。"),
        PaperInfo("fujifilm_crystal_archive_typeii", "Crystal Archive", "富士相纸：通透，偏冷的白。"),
        PaperInfo("kodak_2383", "2383", "电影拷贝片：反差更高，暗部偏冷，电影感最强。"),
        PaperInfo("kodak_2393", "2393", "高端电影拷贝片：比 2383 更深的黑位。"),
        PaperInfo("none", "不印相", "跳过印相，直接扫描底片；负片看到的是负像。"),
    )

    val diffusions = listOf(
        DiffusionInfo("off", "关", "不加柔焦镜。"),
        DiffusionInfo("black_pro_mist", "Black Pro-Mist", "高光溢开但黑位不灰，最常用。"),
        DiffusionInfo("pro_mist", "Pro-Mist", "大范围柔化，整体偏粉彩、低反差。"),
        DiffusionInfo("glimmerglass", "Glimmerglass", "很轻，保留锐度，只给高光加一点光泽。"),
        DiffusionInfo("cinebloom", "CineBloom", "很宽、缓慢衰减的一层雾，梦幻感最强。"),
    )

    val formats = listOf(
        FormatInfo("half", "半格", 24.0, 4.0 / 3.0, "底片小，颗粒被放得更大。"),
        FormatInfo("135", "135", 36.0, 1.5, "标准 135 颗粒。"),
        FormatInfo("xpan", "XPan", 65.0, 65.0 / 24.0, "宽幅 135，颗粒同 135。"),
        FormatInfo("66", "6×6", 56.0, 1.0, "底片大，颗粒更细腻。"),
        FormatInfo("67", "6×7", 70.0, 70.0 / 56.0, "底片大，颗粒更细腻。"),
    )

    val borders = listOf(
        BorderInfo("none", "无", "不加边框。"),
        BorderInfo("white", "相纸白边", "spektrafilm 的白边：四周留长边 3% 的相纸。白边颜色是这张相纸未曝光处的真实颜色；反转片和直接扫描时为纯白。"),
        BorderInfo("edge", "片边", "连同底片片边一起印出来，带胶片名与格号；片边颜色按片基真实印出。"),
    )

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
