package app.spektralab.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import app.spektralab.engine.Look
import app.spektralab.engine.LookSetup
import app.spektralab.gl.FilmRenderer
import app.spektralab.gl.GlUtil
import app.spektralab.gl.InputImage
import app.spektralab.gl.RenderRequest
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Live darkroom preview: renders a photo or a playing video through the film pipeline. */
@SuppressLint("ViewConstructor")
class FilmPreviewView(context: Context) : GLSurfaceView(context) {
    private val r = PreviewRenderer()

    var onVideoState: ((playing: Boolean, positionMs: Long) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(r)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setPhoto(bitmap: Bitmap, autoEv: Float, seed: Int) = queueEvent {
        r.pendingBitmap = bitmap
        r.autoEv = autoEv
        r.seed = seed
        r.video = null
        requestRender()
    }

    fun setVideo(uri: Uri, rotation: Int, width: Int, height: Int, autoEv: Float) = queueEvent {
        r.autoEv = autoEv
        r.videoRequest = VideoRequest(uri, rotation, width, height)
        requestRender()
    }

    fun setLook(look: Look, setup: LookSetup) = queueEvent {
        r.look = look
        r.pendingSetup = setup
        requestRender()
    }

    fun setOriginal(original: Boolean) = queueEvent { r.original = original; requestRender() }

    fun play(playing: Boolean) {
        val p = r.player ?: return
        if (playing) p.start() else p.pause()
        onVideoState?.invoke(p.isPlaying, p.currentPosition.toLong())
    }

    fun release() {
        r.player?.release()
        r.player = null
        queueEvent { r.releaseGl() }
    }

    class VideoRequest(val uri: Uri, val rotation: Int, val width: Int, val height: Int)

    private inner class PreviewRenderer : Renderer {
        var renderer: FilmRenderer? = null
        var pendingSetup: LookSetup? = null
        var look: Look = Look.DEFAULT
        var pendingBitmap: Bitmap? = null
        var photoTex = 0
        var photoW = 0
        var photoH = 0
        var autoEv = 0f
        var seed = 1
        var original = false
        var video: VideoRequest? = null
        var videoRequest: VideoRequest? = null
        var player: MediaPlayer? = null
        private var oesTex = 0
        private var surfaceTexture: SurfaceTexture? = null
        private val stMatrix = FloatArray(16)
        private var frameCount = 0
        private var surfaceW = 1
        private var surfaceH = 1
        private var overlayTex = 0
        private var overlayKey = ""

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            renderer?.release()
            renderer = FilmRenderer()
            photoTex = 0
            oesTex = 0
            overlayTex = 0
            overlayKey = ""
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
        }

        override fun onDrawFrame(gl: GL10?) {
            val fr = renderer ?: return
            pendingSetup?.let { fr.setLook(it); pendingSetup = null }
            pendingBitmap?.let { bmp ->
                GlUtil.deleteTexture(photoTex)
                photoTex = GlWorker.uploadSrgb(bmp)
                photoW = bmp.width; photoH = bmp.height
                pendingBitmap = null
            }
            videoRequest?.let { startVideo(it); videoRequest = null }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            GLES30.glClearColor(0.05f, 0.03f, 0.03f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (fr.setup == null) return
            val v = video
            val input: InputImage
            val srcW: Int
            val srcH: Int
            var frameSeed = seed
            if (v != null) {
                val st = surfaceTexture ?: return
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                srcW = v.width; srcH = v.height
                val framing = Framing.compute(srcW, srcH, look, maxOf(surfaceW, surfaceH))
                input = InputImage(oesTex, true, FilmRenderer.multiply(
                    FilmRenderer.multiply(Framing.surfaceTextureTransform(stMatrix), Framing.rotationTransform(v.rotation)),
                    Framing.cropTransform(framing.crop)))
                frameSeed = seed + frameCount * 7919
                frameCount++
                draw(fr, input, framing, frameSeed)
            } else {
                if (photoTex == 0) return
                val framing = Framing.compute(photoW, photoH, look, maxOf(surfaceW, surfaceH))
                input = InputImage(photoTex, false, Framing.bitmapUvTransform(framing.crop))
                draw(fr, input, framing, frameSeed)
            }
        }

        private fun draw(fr: FilmRenderer, input: InputImage, framing: Framing, seed: Int) {
            // the view is sized to the frame aspect; match the processed area to the surface
            val sx = surfaceW.toFloat() / framing.outWidth
            val iw = (framing.imageWidth * sx).toInt().coerceAtLeast(2)
            val ih = (framing.imageHeight * sx).toInt().coerceAtLeast(2)
            updateOverlay(framing)
            fr.render(RenderRequest(
                input = input, imageWidth = iw, imageHeight = ih,
                framebuffer = 0, outWidth = surfaceW, outHeight = surfaceH,
                imageRect = framing.imageRect, overlayTexture = overlayTex,
                original = original, seed = seed, autoEv = autoEv,
            ))
        }

        private fun updateOverlay(framing: Framing) {
            val key = "${look.filmEdge}/${look.dateStamp}/${look.film}/${look.format}/$surfaceW/$surfaceH"
            if (key == overlayKey) return
            overlayKey = key
            GlUtil.deleteTexture(overlayTex)
            overlayTex = 0
            if (!OverlayPainter.needsOverlay(look)) return
            val scaled = Framing(framing.crop, framing.imageWidth, framing.imageHeight, surfaceW, surfaceH, framing.imageRect)
            OverlayPainter.paint(scaled, look, 12, System.currentTimeMillis())?.let {
                overlayTex = GlWorker.uploadRgba(it)
                it.recycle()
            }
        }

        private fun startVideo(req: VideoRequest) {
            player?.release()
            surfaceTexture?.release()
            if (oesTex == 0) {
                oesTex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            }
            val st = SurfaceTexture(oesTex)
            st.setOnFrameAvailableListener { requestRender() }
            surfaceTexture = st
            video = req
            val surface = Surface(st)
            val mp = MediaPlayer()
            try {
                mp.setDataSource(context, req.uri)
                mp.setSurface(surface)
                mp.isLooping = true
                mp.setOnPreparedListener { it.seekTo(0); onVideoState?.invoke(false, 0) }
                mp.setOnSeekCompleteListener { requestRender() }
                mp.prepareAsync()
                player = mp
            } catch (e: Exception) {
                mp.release()
            }
            surface.release()
        }

        fun releaseGl() {
            renderer?.release(); renderer = null
            GlUtil.deleteTexture(photoTex); photoTex = 0
            GlUtil.deleteTexture(overlayTex); overlayTex = 0
            surfaceTexture?.release(); surfaceTexture = null
            if (oesTex != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
            oesTex = 0
        }
    }
}
