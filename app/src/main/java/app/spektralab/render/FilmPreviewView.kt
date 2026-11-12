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
import app.spektralab.data.SourceImage
import app.spektralab.engine.LookSetup
import app.spektralab.gl.FilmRenderer
import app.spektralab.gl.GlUtil
import app.spektralab.gl.InputImage
import app.spektralab.gl.RenderRequest
import app.spektralab.gl.RenderTarget
import app.spektralab.gl.Program
import app.spektralab.gl.FullscreenQuad
import app.spektralab.gl.Shaders
import app.spektralab.engine.Polaroid
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Live darkroom preview: renders a photo or a playing video through the film pipeline. */
@SuppressLint("ViewConstructor")
class FilmPreviewView(context: Context) : GLSurfaceView(context) {
    private val r = PreviewRenderer()

    var onVideoState: ((playing: Boolean, positionMs: Long) -> Unit)? = null
    /** Called on the main thread when the GPU cannot run the pipeline (message for the user). */
    var onGpuError: ((String) -> Unit)? = null
    /** Called on the main thread once the first processed frame is on screen. */
    var onFirstFrame: (() -> Unit)? = null
    /** Play spektrafilm's instant-film development animation when a photo opens. */
    @Volatile var revealEnabled = true

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(r)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setPhoto(bitmap: SourceImage, autoEv: Float, seed: Int) = queueEvent {
        r.pendingBitmap = bitmap
        r.currentBitmap = bitmap
        r.autoEv = autoEv
        r.seed = seed
        r.video = null
        r.revealActive = false
        r.revealPending = revealEnabled
        r.firstFrameSent = false
        r.dropFull = true
        r.zoom = 1f
        requestRender()
    }

    /**
     * The full-resolution source, loaded once the picture is magnified. Only the texture is
     * kept; after a lost GL context the zoomed view falls back to the preview source.
     */
    fun setFullPhoto(source: SourceImage) = queueEvent {
        r.pendingFull = source
        requestRender()
    }

    /**
     * Magnification relative to the fitted frame (1 = whole frame) around the point
     * ([centerX], [centerY]) of the frame, 0..1 with y down. Above 1 the picture is rendered
     * once at full resolution through the whole pipeline and that render is panned.
     */
    fun setView(zoom: Float, centerX: Float, centerY: Float) = queueEvent {
        r.zoom = zoom; r.centerX = centerX; r.centerY = 1f - centerY
        requestRender()
    }

    fun setVideo(uri: Uri, rotation: Int, width: Int, height: Int, autoEv: Float) = queueEvent {
        r.autoEv = autoEv
        r.videoRequest = VideoRequest(uri, rotation, width, height)
        r.firstFrameSent = false
        requestRender()
    }

    fun setLook(look: Look, setup: LookSetup) = queueEvent {
        if (r.look != look) r.revealActive = false
        r.look = look
        r.pendingSetup = setup
        r.currentSetup = setup
        requestRender()
    }

    fun setOriginal(original: Boolean) = queueEvent {
        r.original = original
        if (original) { r.revealActive = false; r.revealPending = false }
        requestRender()
    }

    fun play(playing: Boolean) {
        r.wantPlay = playing
        val p = r.player ?: return
        // start() before onPrepared puts MediaPlayer into its error state; onPrepared starts it
        if (!r.prepared) return
        if (playing) p.start() else p.pause()
        onVideoState?.invoke(p.isPlaying, p.currentPosition.toLong())
    }

    /** Same frame number and date as the export, so the date back / edge print match. */
    fun setFrameInfo(frameNumber: Int, date: Long) = queueEvent {
        r.frameNumber = frameNumber
        r.stampDate = date
        r.invalidateOverlay()
        requestRender()
    }

    /** Activity went to the background: stop playback (and its sound) and the GL thread. */
    fun pauseForBackground() {
        r.wantPlay = false
        if (r.prepared) r.player?.takeIf { it.isPlaying }?.pause()
        onVideoState?.invoke(false, 0)
        onPause()
    }

    fun resumeFromBackground() = onResume()

    fun release() {
        r.player?.release()
        r.player = null
        queueEvent { r.releaseGl() }
    }

    class VideoRequest(val uri: Uri, val rotation: Int, val width: Int, val height: Int,
                       val resumeMs: Int = 0, val resumePlaying: Boolean = false)

    private inner class PreviewRenderer : Renderer {
        var renderer: FilmRenderer? = null
        var pendingSetup: LookSetup? = null
        var look: Look = Look.DEFAULT
        var pendingBitmap: SourceImage? = null
        var currentBitmap: SourceImage? = null
        var currentSetup: LookSetup? = null
        var photoTex = 0
        var photoW = 0
        var photoH = 0
        var autoEv = 0f
        var seed = 1
        var original = false
        var video: VideoRequest? = null
        var videoRequest: VideoRequest? = null
        @Volatile var player: MediaPlayer? = null
        @Volatile var prepared = false
        @Volatile var wantPlay = false
        var frameNumber = 1
        var stampDate = System.currentTimeMillis()
        fun invalidateOverlay() { overlayKey = "" }
        private var oesTex = 0
        private var surfaceTexture: SurfaceTexture? = null
        private val stMatrix = FloatArray(16)
        private var frameCount = 0
        private var surfaceW = 1
        private var surfaceH = 1
        private var overlayTex = 0
        private var overlayKey = ""
        // instant-film reveal
        var revealPending = false
        var revealActive = false
        var firstFrameSent = false
        private var revealStart = 0L
        private var revealTarget: RenderTarget? = null
        private var revealMax: Polaroid.Maxima? = null
        private var polaroid: Program? = null
        private var quad: FullscreenQuad? = null
        // magnified view
        var zoom = 1f
        var centerX = 0.5f
        var centerY = 0.5f
        var pendingFull: SourceImage? = null
        var dropFull = false
        private var fullTex = 0
        private var fullW = 0
        private var fullH = 0
        private var fullLinear = false
        private var blit: Program? = null
        private var zoomTarget: RenderTarget? = null
        private var zoomKey: List<Any?>? = null
        private var zoomBroken = false

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // A new EGL context (first start, or the view was re-attached after a fold /
            // unfold): every GL object is gone, so re-upload what was on screen.
            val restore = renderer != null
            renderer = try {
                FilmRenderer()
            } catch (e: Exception) {
                android.util.Log.e("SpektraPreview", "renderer init failed", e)
                post { onGpuError?.invoke("这台设备的 GPU 不支持所需的半精度渲染，无法预览") }
                null
            }
            if (renderer == null) return
            polaroid = Program(Shaders.VERTEX, Shaders.POLAROID)
            blit = Program(Shaders.VERTEX, Shaders.BLIT)
            quad = FullscreenQuad()
            fullTex = 0; zoomTarget = null; zoomKey = null
            revealTarget = null
            revealActive = false
            photoTex = 0
            oesTex = 0
            overlayTex = 0
            overlayKey = ""
            if (restore) {
                pendingSetup = currentSetup
                if (video == null) pendingBitmap = currentBitmap
                video?.let { v ->
                    val p = player
                    videoRequest = VideoRequest(v.uri, v.rotation, v.width, v.height,
                        p?.currentPosition ?: 0, p?.isPlaying ?: false)
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                drawFrame()
            } catch (e: Exception) {
                android.util.Log.e("SpektraPreview", "draw failed", e)
                if (!errorReported) {
                    errorReported = true
                    post { onGpuError?.invoke(if (video != null) "这台设备的 GPU 无法处理视频帧" else "预览渲染失败") }
                }
            }
        }

        private var errorReported = false

        private fun drawFrame() {
            val fr = renderer ?: return
            pendingSetup?.let { fr.setLook(it); pendingSetup = null }
            pendingBitmap?.let { bmp ->
                GlUtil.deleteTexture(photoTex)
                photoTex = GlWorker.upload(bmp)
                photoW = bmp.width; photoH = bmp.height
                pendingBitmap = null
            }
            if (dropFull) { dropFull = false; pendingFull = null; zoomBroken = false; releaseZoom(); GlUtil.deleteTexture(fullTex); fullTex = 0 }
            pendingFull?.let { src ->
                pendingFull = null
                GlUtil.deleteTexture(fullTex)
                fullTex = runCatching { GlWorker.upload(src) }.getOrDefault(0)
                fullW = src.width; fullH = src.height; fullLinear = src.linear != null
                zoomKey = null
            }
            videoRequest?.let { startVideo(it); videoRequest = null }
            if (revealActive && drawReveal()) return
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
                    Framing.surfaceTextureTransform(stMatrix),
                    Framing.cropTransform(framing.crop)))
                frameSeed = seed + frameCount * 7919
                frameCount++
                draw(fr, input, framing, frameSeed)
            } else {
                if (photoTex == 0) return
                val framing = Framing.compute(photoW, photoH, look, maxOf(surfaceW, surfaceH))
                input = InputImage(photoTex, false, Framing.bitmapUvTransform(framing.crop), sceneLinear = currentBitmap?.linear != null)
                if (revealPending && !original) {
                    revealPending = false
                    if (startReveal(fr, input, framing, frameSeed)) return
                }
                if (zoom > 1.001f && !zoomBroken) {
                    if (drawZoomed(fr)) { notifyFirstFrame(); return }
                } else if (zoomTarget != null) releaseZoom()
                draw(fr, input, framing, frameSeed)
            }
            notifyFirstFrame()
        }

        private fun notifyFirstFrame() {
            if (firstFrameSent) return
            firstFrameSent = true
            post { onFirstFrame?.invoke() }
        }

        /** Render the finished print offscreen, measure it, and start the development animation. */
        private fun startReveal(fr: FilmRenderer, input: InputImage, framing: Framing, seed: Int): Boolean {
            val t = revealTarget?.takeIf { it.width == surfaceW && it.height == surfaceH }
                ?: run { revealTarget?.release(); RenderTarget(surfaceW, surfaceH, GLES30.GL_RGBA8) }
            revealTarget = t
            draw(fr, input, framing, seed, t.framebuffer)
            val buf = java.nio.ByteBuffer.allocateDirect(surfaceW * surfaceH * 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.framebuffer)
            GLES30.glReadPixels(0, 0, surfaceW, surfaceH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val bytes = ByteArray(surfaceW * surfaceH * 4).also { buf.position(0); buf.get(it) }
            revealMax = Polaroid.maxima(bytes, step = 5)
            revealStart = System.nanoTime()
            revealActive = true
            return drawReveal()
        }

        /** One frame of spektrafilm's polaroid development; false when it has finished. */
        private fun drawReveal(): Boolean {
            val t = revealTarget ?: return false
            val m = revealMax ?: return false
            val p = polaroid ?: return false
            val tt = (System.nanoTime() - revealStart) / 1e6 / Polaroid.DURATION_MS
            if (tt >= 1.0) {
                revealActive = false
                requestRender()
                return false
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            p.use()
            p.texture("uSrc", 0, t.texture)
            p.set1fv("uCoef", Polaroid.coefficients(tt))
            p.set3f("uDensityMax", m.density[0], m.density[1], m.density[2])
            p.set3f("uChromaMax", m.chroma[0], m.chroma[1], m.chroma[2])
            p.set1f("uNeutralMax", m.neutral)
            quad?.draw()
            notifyFirstFrame()
            requestRender()
            return true
        }

        private fun draw(fr: FilmRenderer, input: InputImage, framing: Framing, seed: Int, framebuffer: Int = 0) {
            // the view is sized to the frame aspect; match the processed area to the surface
            val sx = surfaceW.toFloat() / framing.outWidth
            val iw = (framing.imageWidth * sx).toInt().coerceAtLeast(2)
            val ih = (framing.imageHeight * sx).toInt().coerceAtLeast(2)
            updateOverlay(framing)
            fr.render(RenderRequest(
                input = input, imageWidth = iw, imageHeight = ih,
                framebuffer = framebuffer, outWidth = surfaceW, outHeight = surfaceH,
                imageRect = framing.imageRect, border = currentSetup?.let { Borders.color(look, it) } ?: floatArrayOf(0f, 0f, 0f),
                overlayTexture = overlayTex,
                original = original, seed = seed, autoEv = autoEv,
            ))
        }

        /**
         * The magnified view: the whole frame rendered once at full resolution (the same
         * pipeline and pixel size as an export, so grain and halation are the real ones),
         * kept as a texture and shown through a window. False when it could not be done.
         */
        private fun drawZoomed(fr: FilmRenderer): Boolean {
            val b = blit ?: return false
            val q = quad ?: return false
            try {
                val full = fullTex != 0
                val tex = if (full) fullTex else photoTex
                val sw = if (full) fullW else photoW; val sh = if (full) fullH else photoH
                val linear = if (full) fullLinear else currentBitmap?.linear != null
                val cap = minOf(FULL_EDGE, (GlUtil.maxTextureSize / 1.07).toInt())
                val framing = Framing.compute(sw, sh, look, cap)
                val key = listOf(tex, currentSetup, look, original, autoEv, seed, frameNumber, stampDate, framing.outWidth, framing.outHeight)
                if (key != zoomKey) {
                    val t = zoomTarget?.takeIf { it.width == framing.outWidth && it.height == framing.outHeight }
                        ?: run { zoomTarget?.release(); zoomTarget = null; RenderTarget(framing.outWidth, framing.outHeight, GLES30.GL_RGBA8) }
                    zoomTarget = t
                    val overlay = if (OverlayPainter.needsOverlay(look)) OverlayPainter.paint(framing, look, frameNumber, stampDate)
                        ?.let { bmp -> GlWorker.uploadRgba(bmp).also { bmp.recycle() } } ?: 0 else 0
                    fr.render(RenderRequest(
                        input = InputImage(tex, false, Framing.bitmapUvTransform(framing.crop), sceneLinear = linear),
                        imageWidth = framing.imageWidth, imageHeight = framing.imageHeight,
                        framebuffer = t.framebuffer, outWidth = t.width, outHeight = t.height,
                        imageRect = framing.imageRect, border = currentSetup?.let { Borders.color(look, it) } ?: floatArrayOf(0f, 0f, 0f),
                        overlayTexture = overlay, original = original, seed = seed, autoEv = autoEv,
                    ))
                    GlUtil.deleteTexture(overlay)
                    // the full-size intermediates are large: give them back until the next change
                    fr.releaseTargets()
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.texture)
                    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                    zoomKey = key
                }
                val t = zoomTarget ?: return false
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, surfaceW, surfaceH)
                b.use()
                b.texture("uSrc", 0, t.texture)
                val span = 1f / zoom
                b.set2f("uScale", span, span)
                b.set2f("uOffset", (centerX - span / 2).coerceIn(0f, 1f - span), (centerY - span / 2).coerceIn(0f, 1f - span))
                q.draw()
                return true
            } catch (e: Throwable) {
                // not enough GPU memory for a full-size render: stay with the fitted preview
                android.util.Log.w("SpektraPreview", "magnified view unavailable", e)
                zoomBroken = true
                releaseZoom()
                runCatching { fr.releaseTargets() }
                return false
            }
        }

        private fun releaseZoom() {
            zoomTarget?.release(); zoomTarget = null; zoomKey = null
        }

        private fun updateOverlay(framing: Framing) {
            val key = "${look.border}/${look.dateStamp}/${look.film}/${look.format}/$surfaceW/$surfaceH"
            if (key == overlayKey) return
            overlayKey = key
            GlUtil.deleteTexture(overlayTex)
            overlayTex = 0
            if (!OverlayPainter.needsOverlay(look)) return
            val scaled = Framing(framing.crop, framing.imageWidth, framing.imageHeight, surfaceW, surfaceH, framing.imageRect)
            OverlayPainter.paint(scaled, look, frameNumber, stampDate)?.let {
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
            prepared = false
            wantPlay = req.resumePlaying
            try {
                mp.setDataSource(context, req.uri)
                mp.setSurface(surface)
                mp.isLooping = true
                mp.setOnPreparedListener {
                    prepared = true
                    it.seekTo(req.resumeMs)
                    if (wantPlay) it.start()
                    onVideoState?.invoke(wantPlay, req.resumeMs.toLong())
                }
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
            revealTarget?.release(); revealTarget = null
            polaroid?.release(); polaroid = null
            blit?.release(); blit = null
            releaseZoom()
            GlUtil.deleteTexture(fullTex); fullTex = 0
            quad?.release(); quad = null
            GlUtil.deleteTexture(photoTex); photoTex = 0
            GlUtil.deleteTexture(overlayTex); overlayTex = 0
            surfaceTexture?.release(); surfaceTexture = null
            if (oesTex != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
            oesTex = 0
        }
    }

    companion object {
        /** Long edge of the magnified view's render and of the source loaded for it (an untiled full-size export's limit). */
        const val FULL_EDGE = 4096
    }
}
