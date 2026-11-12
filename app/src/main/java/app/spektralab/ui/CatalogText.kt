package app.spektralab.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.spektralab.R
import app.spektralab.engine.BorderInfo
import app.spektralab.engine.DiffusionInfo
import app.spektralab.engine.FilmInfo
import app.spektralab.engine.FormatInfo
import app.spektralab.engine.PaperInfo

/** Presentation text for the engine catalogue, kept out of the pure Kotlin engine module. */
@Composable
fun filmKind(film: FilmInfo): String = stringResource(when (film.kind) {
    "positive" -> R.string.film_kind_positive
    "cine" -> R.string.film_kind_cine
    else -> R.string.film_kind_negative
})

@Composable
fun filmNote(stock: String): String = stringResource(when (stock) {
    "kodak_portra_400" -> R.string.film_note_portra_400
    "kodak_portra_160" -> R.string.film_note_portra_160
    "kodak_portra_800" -> R.string.film_note_portra_800
    "kodak_ektar_100" -> R.string.film_note_ektar_100
    "kodak_gold_200" -> R.string.film_note_gold_200
    "kodak_ultramax_400" -> R.string.film_note_ultramax_400
    "fujifilm_c200" -> R.string.film_note_c200
    "fujifilm_pro_400h" -> R.string.film_note_pro_400h
    "fujifilm_xtra_400" -> R.string.film_note_xtra_400
    "kodak_vision3_50d" -> R.string.film_note_vision3_50d
    "kodak_vision3_250d" -> R.string.film_note_vision3_250d
    "kodak_vision3_200t" -> R.string.film_note_vision3_200t
    "kodak_vision3_500t" -> R.string.film_note_vision3_500t
    "kodak_verita_200d" -> R.string.film_note_verita_200d
    "fujifilm_velvia_100" -> R.string.film_note_velvia_100
    "fujifilm_provia_100f" -> R.string.film_note_provia_100f
    "kodak_ektachrome_100" -> R.string.film_note_ektachrome_100
    else -> R.string.film_note_kodachrome_64
})

@Composable
fun paperLabel(paper: PaperInfo): String = when (paper.id) {
    "auto" -> stringResource(R.string.paper_auto)
    "none" -> stringResource(R.string.paper_none)
    else -> paper.label
}

@Composable
fun paperNote(id: String): String = stringResource(when (id) {
    "auto" -> R.string.paper_note_auto
    "kodak_portra_endura" -> R.string.paper_note_portra_endura
    "kodak_supra_endura" -> R.string.paper_note_supra_endura
    "kodak_ultra_endura" -> R.string.paper_note_ultra_endura
    "kodak_endura_premier" -> R.string.paper_note_endura_premier
    "kodak_ektacolor_edge" -> R.string.paper_note_ektacolor_edge
    "fujifilm_crystal_archive_typeii" -> R.string.paper_note_crystal_archive
    "kodak_2383" -> R.string.paper_note_2383
    "kodak_2393" -> R.string.paper_note_2393
    else -> R.string.paper_note_none
})

@Composable
fun diffusionLabel(diffusion: DiffusionInfo): String =
    if (diffusion.id == "off") stringResource(R.string.diffusion_off) else diffusion.label

@Composable
fun diffusionNote(id: String): String = stringResource(when (id) {
    "off" -> R.string.diffusion_note_off
    "black_pro_mist" -> R.string.diffusion_note_black_pro_mist
    "pro_mist" -> R.string.diffusion_note_pro_mist
    "glimmerglass" -> R.string.diffusion_note_glimmerglass
    else -> R.string.diffusion_note_cinebloom
})

@Composable
fun formatLabel(format: FormatInfo): String =
    if (format.id == "half") stringResource(R.string.format_half) else format.label

@Composable
fun formatNote(id: String): String = stringResource(when (id) {
    "half" -> R.string.format_note_half
    "135" -> R.string.format_note_135
    "xpan" -> R.string.format_note_xpan
    "66" -> R.string.format_note_66
    else -> R.string.format_note_67
})

@Composable
fun borderLabel(border: BorderInfo): String = stringResource(when (border.id) {
    "white" -> R.string.border_white
    "edge" -> R.string.border_edge
    else -> R.string.border_none
})

@Composable
fun borderNote(id: String): String = stringResource(when (id) {
    "white" -> R.string.border_note_white
    "edge" -> R.string.border_note_edge
    else -> R.string.border_note_none
})
