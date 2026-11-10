package app.spektralab.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import app.spektralab.engine.Catalog
import app.spektralab.engine.Look
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Film-edge markings and a date-back stamp, painted over the rendered frame. */
object OverlayPainter {
    var monoTypeface: Typeface = Typeface.MONOSPACE

    fun needsOverlay(look: Look) = look.filmEdge || look.dateStamp

    /** Returns a premultiplied ARGB bitmap the size of the output canvas, or null. */
    fun paint(framing: Framing, look: Look, frameNumber: Int, date: Long): Bitmap? {
        if (!needsOverlay(look)) return null
        val w = framing.outWidth
        val h = framing.outHeight
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = framing.imageRect
        val left = r[0] * w
        val right = r[2] * w
        val top = (1 - r[3]) * h
        val bottom = (1 - r[1]) * h
        val short = minOf(right - left, bottom - top)
        if (look.filmEdge) {
            val film = Catalog.film(look.film)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = monoTypeface
                color = Color.argb(230, 238, 160, 72)
                textSize = (top.coerceAtLeast(left)) * 0.42f
            }
            val landscape = w >= h
            val name = film.name.uppercase(Locale.ROOT)
            val num = frameNumber.coerceIn(1, 36)
            if (landscape) {
                val ty = top * 0.68f
                c.drawText("$name   ▸ $num", left + short * 0.04f, ty, paint)
                c.drawText("${num}A", right - paint.measureText("${num}A") - short * 0.04f, ty, paint)
                val by = bottom + (h - bottom) * 0.68f
                c.drawText("▮▮ ▮ ▮▮▮  ${film.iso}", left + short * 0.04f, by, paint)
            } else {
                c.save()
                c.rotate(90f)
                val tx = -(w - right) * 0.32f - right
                c.drawText("$name   ▸ $num", top + short * 0.04f, tx, paint)
                c.restore()
            }
        }
        if (look.dateStamp) {
            val text = SimpleDateFormat("''yy MM dd", Locale.US).format(Date(date))
            val size = short * 0.055f
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = monoTypeface
                textSize = size
                color = Color.argb(150, 255, 110, 30)
                maskFilter = BlurMaskFilter(size * 0.25f, BlurMaskFilter.Blur.NORMAL)
            }
            val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = monoTypeface
                textSize = size
                color = Color.argb(235, 255, 168, 70)
            }
            val tw = core.measureText(text)
            val x = right - tw - short * 0.06f
            val y = bottom - short * 0.06f
            c.drawText(text, x, y, glow)
            c.drawText(text, x, y, core)
        }
        return bmp
    }
}
