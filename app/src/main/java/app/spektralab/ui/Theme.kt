package app.spektralab.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.spektralab.R

/** Palette of the prototype: lightbox (library) and darkroom (editor). */
object Pal {
    // lightbox
    val red = Color(0xFFD4281C)
    val ink = Color(0xFF1B1A17)
    val paper = Color(0xFFF5F3EB)
    val glow = Color(0xFFFFFDF6)
    val grey = Color(0xFF6B675E)
    val strip = Color(0xFF221A14)
    val stripEdge = Color(0xFFE9A65A)
    val card = Color(0xFFF1E6CC)
    // darkroom
    val text = Color(0xFFF5DDD2)
    val dim = Color(0xFFB8776A)
    val line = Color(0xFF3D1A14)
    val off = Color(0xFF6E3A31)
    val dink = Color(0xFF140807)
    val dark = Color(0xFF0D0807)
    val darkPanel = Color(0xFF170C0A)
    val safelight = Color(0xFFFF3B22)
    val yellowFilter = Color(0xFFFFCD28)
    val blueShift = Color(0xFF2850FF)
    val magentaFilter = Color(0xFFE632B4)
    val greenShift = Color(0xFF28C85A)
}

object Fonts {
    val hand = FontFamily(Font(R.font.long_cang))
    val mono = FontFamily(Font(R.font.martian_mono))
}
