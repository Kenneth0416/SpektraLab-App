package app.spektralab.render

import android.graphics.Bitmap
import android.opengl.EGLSurface
import android.opengl.GLES30.*
import app.spektralab.engine.LookSetup
import app.spektralab.data.SourceImage
import app.spektralab.gl.EglCore
import app.spektralab.gl.FilmRenderer
import app.spektralab.gl.GlUtil
import app.spektralab.gl.InputImage
import app.spektralab.gl.RenderRequest
import app.spektralab.gl.RenderTarget
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** Single GL thread with an offscreen context, used for thumbnails and exports. */
class GlWorker {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "spektra-gl").apply { priority = Thread.NORM_PRIORITY - 1 } }
    val dispatcher = executor.asCoroutineDispatcher()
    lateinit var egl: EglCore
        private set
    private lateinit var pbuffer: EGLSurface
    lateinit var renderer: FilmRenderer
        private set
    private var ready = false

    suspend fun <T> run(block: GlWorker.() -> T): T = withContext(dispatcher) {
        if (!ready) {
            egl = EglCore()
            pbuffer = egl.createPbuffer()
            egl.makeCurrent(pbuffer)
            renderer = FilmRenderer()
            ready = true
        }
        egl.makeCurrent(pbuffer)
        block()
    }

    /** Render an upright photo through [setup]; returns an ARGB bitmap of the output canvas. */
    fun renderPhoto(source: SourceImage, setup: LookSetup, autoEv: Float, framing: Framing, overlay: Bitmap?, seed: Int,
                    border: FloatArray = Borders.color(setup.look, setup)): Bitmap {
        val maxTex = GlUtil.maxTextureSize
        require(framing.outWidth <= maxTex && framing.outHeight <= maxTex) { "image too large for GPU" }
        val tex = upload(source)
        val overlayTex = overlay?.let { uploadRgba(it) } ?: 0
        val out = RenderTarget(framing.outWidth, framing.outHeight, GL_RGBA8)
        try {
            renderer.setLook(setup)
            renderer.render(RenderRequest(
                input = InputImage(tex, false, Framing.bitmapUvTransform(framing.crop), sceneLinear = source.linear != null),
                imageWidth = framing.imageWidth, imageHeight = framing.imageHeight,
                framebuffer = out.framebuffer, outWidth = out.width, outHeight = out.height,
                imageRect = framing.imageRect, border = border, overlayTexture = overlayTex, flipY = true,
                seed = seed, autoEv = autoEv,
            ))
            glBindFramebuffer(GL_FRAMEBUFFER, out.framebuffer)
            val buf = ByteBuffer.allocateDirect(out.width * out.height * 4).order(ByteOrder.nativeOrder())
            glPixelStorei(GL_PACK_ALIGNMENT, 1)
            glReadPixels(0, 0, out.width, out.height, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            val bmp = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
            buf.position(0)
            bmp.copyPixelsFromBuffer(buf)
            return bmp
        } finally {
            out.release()
            GlUtil.deleteTexture(tex)
            if (overlayTex != 0) GlUtil.deleteTexture(overlayTex)
            // big exports: give the memory back
            if (framing.imageWidth * framing.imageHeight > 4_000_000) renderer.releaseTargets()
        }
    }

    /**
     * [renderPhoto] for images too large for single textures: the pipeline runs in tiles
     * (see [app.spektralab.gl.TilePlan]) and each output tile is read straight into the
     * returned bitmap, the only full-size copy. No overlay: draw it on the bitmap.
     */
    fun renderPhotoTiled(source: SourceImage, setup: LookSetup, autoEv: Float, framing: Framing, seed: Int,
                         border: FloatArray = Borders.color(setup.look, setup), progress: (Float) -> Unit = {}): Bitmap {
        renderer.setLook(setup)
        val plan = renderer.planTiled(framing.imageWidth, framing.imageHeight, framing.outWidth, framing.outHeight, framing.imageRect,
            source.width, source.height, Framing.bitmapUvTransform(framing.crop), TILE)
        val out = Bitmap.createBitmap(framing.outWidth, framing.outHeight, Bitmap.Config.ARGB_8888)
        try {
            var scratch: ByteBuffer? = null
            var argb = IntArray(0)
            renderer.renderTiled(plan, autoEv, seed, border, upload = { x, y, w, h ->
                val linear = source.linear
                if (linear != null) uploadLinearWindow(linear, x, y, w, h) else {
                    val bmp = source.bitmap!!
                    val sub = Bitmap.createBitmap(bmp, x, y, w, h)
                    try {
                        val need = sub.rowBytes * h
                        val buf = scratch?.takeIf { it.capacity() >= need } ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
                        scratch = buf
                        buf.clear()
                        sub.copyPixelsToBuffer(buf)
                        buf.position(0)
                        uploadSrgbRows(buf, w, h, sub.rowBytes / 4)
                    } finally {
                        if (sub !== bmp) sub.recycle()
                    }
                }
            }, sink = { x, y, w, h, rgba ->
                // RGBA bytes -> ARGB ints (opaque: premultiplied or not is the same)
                if (argb.size < w * h) argb = IntArray(w * h)
                val ints = rgba.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                for (i in 0 until w * h) {
                    val v = ints.get(i)
                    argb[i] = (v and 0xff00ff00.toInt()) or ((v and 0xff) shl 16) or ((v ushr 16) and 0xff)
                }
                // framebuffer row 0 is the bitmap's top row (the plan renders with flipY)
                out.setPixels(argb, 0, w, x, y, w, h)
            }, progress = progress)
            return out
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
    }

    companion object {
        /** Tile size of [renderPhotoTiled]: 65-80 MB of GPU buffers in all at 50-108 MP (TilePlanTest). */
        const val TILE = 1024

        fun upload(source: SourceImage): Int = source.linear?.let { uploadLinear(it) } ?: uploadSrgb(source.bitmap!!)

        /** Scene-linear half-float image (developed raw) as an RGBA16F texture. */
        fun uploadLinear(img: app.spektralab.raw.LinearImage): Int {
            val tex = GlUtil.texture2d()
            img.halfRgba.position(0)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, img.width, img.height, 0, GL_RGBA, GL_HALF_FLOAT, img.halfRgba)
            return tex
        }

        /** Pixels [x, x + w) x [y, y + h) of [img], read in place from its buffer. */
        fun uploadLinearWindow(img: app.spektralab.raw.LinearImage, x: Int, y: Int, w: Int, h: Int): Int {
            val tex = GlUtil.texture2d()
            img.halfRgba.position(0)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glPixelStorei(GL_UNPACK_ROW_LENGTH, img.width)
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, x)
            glPixelStorei(GL_UNPACK_SKIP_ROWS, y)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, img.halfRgba)
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0)
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0)
            return tex
        }

        private fun uploadSrgbRows(buf: ByteBuffer, w: Int, h: Int, rowLength: Int): Int {
            val tex = GlUtil.texture2d()
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
            return tex
        }

        fun uploadSrgb(bmp: Bitmap): Int {
            val b = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            val buf = ByteBuffer.allocateDirect(b.byteCount).order(ByteOrder.nativeOrder())
            b.copyPixelsToBuffer(buf)
            buf.position(0)
            val tex = GlUtil.texture2d()
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, b.width, b.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            return tex
        }

        fun uploadRgba(bmp: Bitmap): Int {
            val buf = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
            bmp.copyPixelsToBuffer(buf)
            buf.position(0)
            val tex = GlUtil.texture2d()
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, bmp.width, bmp.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            return tex
        }
    }
}
