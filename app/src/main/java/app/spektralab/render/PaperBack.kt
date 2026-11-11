package app.spektralab.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.LruCache
import app.spektralab.R
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Back of a virtual photo paper: port of spektrafilm_gui `virtual_photo_paper_back.py`
 * (logo stamps on a rotated rhombic lattice, light paper glare). spektrafilm shows it as
 * the canvas before a picture is developed; here it is the placeholder while a frame loads.
 */
object PaperBack {
    private const val GRID_SCALE = 0.22          // logo long edge / canvas long edge
    private const val CENTER_DISTANCE = 0.25     // tile-centre spacing / canvas long edge
    private const val ROMBIC_GRID_ANGLE = 72.0   // degrees
    private const val GLOBAL_ROTATION = 16.0     // degrees
    private const val GLARE = 0.25
    private val LOGO = 215
    private val BACKGROUND = 242

    private var logo: Bitmap? = null
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Synchronized
    fun render(context: Context, width: Int, height: Int, seed: Int = 0): Bitmap {
        val key = "${width}x$height/$seed"
        cache.get(key)?.let { return it }
        val src = logo ?: BitmapFactory.decodeResource(context.resources, R.drawable.spektrafilm_paper_watermark,
            BitmapFactory.Options().apply { inScaled = false }).also { logo = it }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(BACKGROUND, BACKGROUND, BACKGROUND))
        val long = max(width, height).toDouble()
        val stamp = GRID_SCALE * long
        val scale = (stamp / max(src.width, src.height)).toFloat()
        // logo alpha drawn in the logo colour: repeated SRC_OVER reproduces spektrafilm's
        // multiplicative transmittance  logo + (bg - logo) * prod(1 - alpha_i)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.rgb(LOGO, LOGO, LOGO), PorterDuff.Mode.SRC_IN)
        }
        // rhombic basis, rotated
        val half = ROMBIC_GRID_ANGLE / 2 * PI / 180
        val d = CENTER_DISTANCE * long
        val th = GLOBAL_ROTATION * PI / 180
        fun rot(x: Double, y: Double) = doubleArrayOf(cos(th) * x + sin(th) * y, -sin(th) * x + cos(th) * y)
        val b0 = rot(d * cos(half), d * sin(half))
        val b1 = rot(d * cos(half), -d * sin(half))
        val radius = 0.5 * hypot(stamp, stamp)
        val limit = ceil((hypot(width.toDouble(), height.toDouble()) + 2 * radius) / d).toInt() + 2
        val ox = 0.5 * width
        val oy = 0.5 * height
        for (i in -limit..limit) for (j in -limit..limit) {
            val cx = ox + i * b0[0] + j * b1[0]
            val cy = oy + i * b0[1] + j * b1[1]
            if (cx < -radius || cx > width + radius || cy < -radius || cy > height + radius) continue
            c.save()
            c.translate(cx.toFloat(), cy.toFloat())
            c.rotate(-GLOBAL_ROTATION.toFloat())
            c.scale(scale, scale)
            c.drawBitmap(src, -src.width / 2f, -src.height / 2f, paint)
            c.restore()
        }
        applyGlare(bmp, seed)
        cache.put(key, bmp)
        return bmp
    }

    /** Paper glare: +-7 % sheen variation and sparse bright specks, stronger on bare paper. */
    private fun applyGlare(bmp: Bitmap, seed: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val rnd = java.util.Random(seed.toLong())
        val bgMinusLogo = (BACKGROUND - LOGO).toFloat()
        for (i in px.indices) {
            val v = px[i] and 0xff
            val transmittance = ((v - LOGO) / bgMinusLogo).coerceIn(0f, 1f)
            val g = (0.88 * rnd.nextFloat() + 0.12 * rnd.nextFloat().toDouble().pow(10.0)).toFloat()
            val paper = 0.30f + 0.70f * transmittance
            val variation = 1f + (2f * g - 1f) * paper * 0.07f * GLARE.toFloat()
            val lift = g.toDouble().pow(6.0).toFloat() * paper * 0.42f * GLARE.toFloat()
            var c = v / 255f * variation
            c += (1f - c) * lift
            val o = (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            px[i] = (0xff shl 24) or (o shl 16) or (o shl 8) or o
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
