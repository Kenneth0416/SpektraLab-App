package app.spektralab.render

import android.graphics.Bitmap
import android.opengl.EGLSurface
import android.opengl.GLES30.*
import app.spektralab.engine.LookSetup
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
    fun renderPhoto(source: Bitmap, setup: LookSetup, autoEv: Float, framing: Framing, overlay: Bitmap?, seed: Int,
                    border: FloatArray = Borders.color(setup.look, setup)): Bitmap {
        val maxTex = GlUtil.maxTextureSize
        require(framing.outWidth <= maxTex && framing.outHeight <= maxTex) { "image too large for GPU" }
        val tex = uploadSrgb(source)
        val overlayTex = overlay?.let { uploadRgba(it) } ?: 0
        val out = RenderTarget(framing.outWidth, framing.outHeight, GL_RGBA8)
        try {
            renderer.setLook(setup)
            renderer.render(RenderRequest(
                input = InputImage(tex, false, Framing.bitmapUvTransform(framing.crop)),
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

    companion object {
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
