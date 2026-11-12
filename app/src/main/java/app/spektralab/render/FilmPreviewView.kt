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
import app.spektralab.R
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

/** How much sharper than the screen the zoomed print is developed. */
object PreviewDetail {
    /** Matches the editor's maximum zoom: no point developing finer than it can show. */
    const val MAX = 6f
    /** Long edge cap of the zoomed print (GPU memory: the pipeline keeps several half-float copies). */
    const val MAX_EDGE = 4096

    /**
     * [sourceRatio]: source pixels per screen pixel (developing finer than the source only
     * blurs). [surfaceLong]: the view's long edge, px; [maxEdge]: the print's long edge cap.
     */
    fun of(sourceRatio: Float, surfaceLong: Int, maxEdge: Int, maxDetail: Float = MAX): Float =
        minOf(sourceRatio, maxEdge.toFloat() / surfaceLong.coerceAtLeast(1), maxDetail).coerceAtLeast(1f)
}

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

    /**
     * Visible part of the print, canvas uv y up (x0, y0, width, height); (0, 0, 1, 1) is the
     * whole frame. Zoom is done here rather than by scaling the view: a GLSurfaceView is its
     * own window surface, so a Compose graphicsLayer would not move it, and even a scaled
     * texture would only magnify screen-resolution pixels.
     */
    fun setZoom(window: FloatArray) {
        val w = window.copyOf()
        queueEvent { r.zoomWindow = w; requestRender() }
    }

    /** Change source identity before asynchronous preview/full-size decodes can complete. */
    fun setSource(key: Pair<Long, String>) = queueEvent {
        if (r.sourceKey != key) {
            r.sourceKey = key
            r.pendingBitmap?.close(); r.pendingBitmap = null
            r.currentBitmap?.close(); r.currentBitmap = null
            r.pendingFull?.close(); r.pendingFull = null
            GlUtil.deleteTexture(r.fullTex); r.fullTex = 0
            GlUtil.deleteTexture(r.photoTex); r.photoTex = 0
            r.printDirty = true
            r.zoomBroken = false
            r.revealActive = false
            r.revealPending = false
            r.firstFrameSent = false
            requestRender()
        }
    }

    fun setFullPhoto(source: SourceImage, key: Pair<Long, String>) = queueEvent {
        if (r.sourceKey == key) { r.pendingFull?.close(); r.pendingFull = source; requestRender() }
        else source.close()
    }

    /**
     * Swap in a sharper rendition of the photo already on screen (same picture, same
     * exposure): the developing animation and the first-frame state carry on undisturbed.
     */
    fun refinePhoto(source: SourceImage, key: Pair<Long, String>) = queueEvent {
        if (r.sourceKey != key) { source.close(); return@queueEvent }
        r.printDirty = true
        if (r.pendingBitmap !== source) r.pendingBitmap?.close()
        r.pendingBitmap = source
        if (r.currentBitmap !== source) {
            r.currentBitmap?.close()
            r.currentBitmap = source.share()
        }
        requestRender()
    }

    fun setPhoto(bitmap: SourceImage, autoEv: Float, seed: Int, key: Pair<Long, String>) = queueEvent {
        if (r.sourceKey != key) { bitmap.close(); return@queueEvent }
        r.printDirty = true
        if (r.pendingBitmap !== bitmap) r.pendingBitmap?.close()
        r.pendingBitmap = bitmap
        if (r.currentBitmap !== bitmap) {
            r.currentBitmap?.close()
            r.currentBitmap = bitmap.share()
        }
        r.autoEv = autoEv
        r.seed = seed
        r.video = null
        r.revealActive = false
        r.revealPending = revealEnabled
        r.firstFrameSent = false
        requestRender()
    }

    fun setVideo(uri: Uri, rotation: Int, width: Int, height: Int, autoEv: Float) = queueEvent {
        r.printDirty = true
        r.autoEv = autoEv
        r.videoRequest = VideoRequest(uri, rotation, width, height)
        r.firstFrameSent = false
        requestRender()
    }

    fun setLook(look: Look, setup: LookSetup) = queueEvent {
        if (r.look != look) r.revealActive = false
        r.look = look
        r.printDirty = true
        r.pendingSetup = setup
        r.currentSetup = setup
        requestRender()
    }

    fun setOriginal(original: Boolean) = queueEvent {
        r.original = original
        r.printDirty = true
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
        r.printDirty = true
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

    companion object {
        const val FULL_EDGE = 4096
        /** Video is developed every frame, so its zoomed print stays smaller. */
        const val VIDEO_DETAIL = 2f
        /** The visible window of the zoomed print; outside it (rubber band) the dark mat. */
        val BLIT = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec4 uWindow;
void main() {
    vec2 uv = uWindow.xy + vUv * uWindow.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) fragColor = vec4(0.05, 0.03, 0.03, 1.0);
    else fragColor = texture(uSrc, uv);
}
"""
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
        var sourceKey: Pair<Long, String>? = null
        var pendingFull: SourceImage? = null
        var fullTex = 0
        private var fullW = 0
        private var fullH = 0
        private var fullLinear = false
        var zoomBroken = false
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
        // zoom: the print is developed once at higher resolution into [print], then only
        // the visible window is drawn while the fingers move (no pipeline run per frame)
        var zoomWindow = floatArrayOf(0f, 0f, 1f, 1f)
        var printDirty = true
        @Volatile private var newVideoFrame = false
        private var print: RenderTarget? = null
        private var blit: Program? = null

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // A new EGL context (first start, or the view was re-attached after a fold /
            // unfold): every GL object is gone, so re-upload what was on screen.
            val restore = renderer != null
            renderer = try {
                FilmRenderer()
            } catch (e: Exception) {
                android.util.Log.e("SpektraPreview", "renderer init failed", e)
                post { onGpuError?.invoke(context.getString(R.string.gpu_half_float_unsupported)) }
                null
            }
            if (renderer == null) return
            polaroid = Program(Shaders.VERTEX, Shaders.POLAROID)
            quad = FullscreenQuad()
            blit = Program(Shaders.VERTEX, BLIT)
            print = null
            fullTex = 0
            zoomBroken = false
            printDirty = true
            revealTarget = null
            revealActive = false
            photoTex = 0
            oesTex = 0
            overlayTex = 0
            overlayKey = ""
            if (restore) {
                pendingSetup = currentSetup
                if (video == null && pendingBitmap !== currentBitmap) {
                    pendingBitmap?.close()
                    pendingBitmap = currentBitmap?.share()
                }
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
            printDirty = true
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                drawFrame()
            } catch (e: Exception) {
                android.util.Log.e("SpektraPreview", "draw failed", e)
                if (!errorReported) {
                    errorReported = true
                    post {
                        onGpuError?.invoke(context.getString(if (video != null) R.string.gpu_video_frame_failed else R.string.preview_render_failed))
                    }
                }
            }
        }

        private var errorReported = false

        private fun drawFrame() {
            val fr = renderer ?: return
            pendingSetup?.let { fr.setLook(it); pendingSetup = null }
            pendingBitmap?.let { bmp ->
                val uploaded = runCatching { GlWorker.upload(bmp) }
                bmp.close()
                val tex = uploaded.getOrThrow()
                GlUtil.deleteTexture(photoTex)
                photoTex = tex
                photoW = bmp.width; photoH = bmp.height
                pendingBitmap = null
            }
            pendingFull?.let { src ->
                pendingFull = null
                val uploaded = runCatching { GlWorker.upload(src) }
                src.close()
                GlUtil.deleteTexture(fullTex)
                fullTex = uploaded.getOrDefault(0)
                fullW = src.width; fullH = src.height; fullLinear = src.linear != null
                printDirty = true
            }
            videoRequest?.let { startVideo(it); videoRequest = null }
            val w = zoomWindow
            val zoomed = w[0] != 0f || w[1] != 0f || w[2] != 1f || w[3] != 1f
            if (zoomed) { revealActive = false; revealPending = false } else releasePrint(fr)
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
                if (newVideoFrame) { newVideoFrame = false; printDirty = true }
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                srcW = v.width; srcH = v.height
                val framing = Framing.compute(srcW, srcH, look, maxOf(surfaceW, surfaceH))
                input = InputImage(oesTex, true, FilmRenderer.multiply(
                    Framing.surfaceTextureTransform(stMatrix),
                    Framing.cropTransform(framing.crop)))
                frameSeed = seed + frameCount * 7919
                frameCount++
                if (!zoomed || !tryDrawZoomed(fr, input, srcW, srcH, frameSeed, VIDEO_DETAIL)) draw(fr, input, framing, frameSeed)
            } else {
                if (photoTex == 0) return
                val framing = Framing.compute(photoW, photoH, look, maxOf(surfaceW, surfaceH))
                input = InputImage(photoTex, false, Framing.bitmapUvTransform(framing.crop), sceneLinear = currentBitmap?.linear != null)
                if (revealPending && !original) {
                    revealPending = false
                    if (startReveal(fr, input, framing, frameSeed)) return
                }
                val sw = if (fullTex != 0) fullW else photoW
                val sh = if (fullTex != 0) fullH else photoH
                val zoomInput = if (fullTex != 0) InputImage(fullTex, false,
                    Framing.bitmapUvTransform(Framing.compute(sw, sh, look, FULL_EDGE).crop), sceneLinear = fullLinear) else input
                if (!zoomed || !tryDrawZoomed(fr, zoomInput, sw, sh, frameSeed, PreviewDetail.MAX)) draw(fr, input, framing, frameSeed)
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

        /**
         * Zoomed: develop the whole print at [PreviewDetail] x the surface into [print] (only
         * when something other than the zoom changed), then draw the visible window of it.
         * Rendering the full frame, not just the crop, keeps grain, halation and the border
         * at their real size on the film; the window just magnifies a sharper print.
         */
        private fun tryDrawZoomed(fr: FilmRenderer, input: InputImage, srcW: Int, srcH: Int, seed: Int, maxDetail: Float): Boolean {
            if (zoomBroken) return false
            return try {
                drawZoomed(fr, input, srcW, srcH, seed, maxDetail)
                true
            } catch (e: Throwable) {
                android.util.Log.w("SpektraPreview", "magnified view unavailable", e)
                zoomBroken = true
                runCatching { releasePrint(fr); fr.releaseTargets() }
                GlUtil.deleteTexture(fullTex); fullTex = 0
                false
            }
        }

        private fun drawZoomed(fr: FilmRenderer, input: InputImage, srcW: Int, srcH: Int, seed: Int, maxDetail: Float) {
            val f1 = Framing.compute(srcW, srcH, look, maxOf(surfaceW, surfaceH))
            val onScreen = maxOf(f1.imageWidth, f1.imageHeight) * surfaceW.toFloat() / f1.outWidth
            val native = Framing.compute(srcW, srcH, look, Int.MAX_VALUE).let { maxOf(it.imageWidth, it.imageHeight) }
            val d = PreviewDetail.of(native / onScreen, maxOf(surfaceW, surfaceH),
                minOf((GlUtil.maxTextureSize / 1.07).toInt(), FULL_EDGE), maxDetail)
            val pw = (surfaceW * d).toInt().coerceAtLeast(2)
            val ph = (surfaceH * d).toInt().coerceAtLeast(2)
            var t = print
            if (t == null || t.width != pw || t.height != ph) {
                t?.release()
                print = null
                t = RenderTarget(pw, ph, GLES30.GL_RGBA8)
                print = t
                printDirty = true
            }
            if (printDirty) {
                draw(fr, input, Framing.compute(srcW, srcH, look, maxOf(pw, ph)), seed, t.framebuffer, pw, ph)
                // mips: between 1x and [d] the print is shown smaller than it was developed,
                // and plain linear minification would alias the grain into shimmer
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.texture)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                printDirty = false
                if (video == null) fr.releaseTargets()
            }
            val p = blit ?: return
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            p.use()
            p.texture("uSrc", 0, t.texture)
            val w = zoomWindow
            p.set4f("uWindow", w[0], w[1], w[2], w[3])
            quad?.draw()
        }

        /** Back at 1x: free the high-resolution print and the pipeline's large intermediates. */
        private fun releasePrint(fr: FilmRenderer) {
            val t = print ?: return
            t.release()
            print = null
            fr.releaseTargets()
            printDirty = true
        }

        private fun draw(fr: FilmRenderer, input: InputImage, framing: Framing, seed: Int, framebuffer: Int = 0,
                         outW: Int = surfaceW, outH: Int = surfaceH) {
            // the view is sized to the frame aspect; match the processed area to the output
            val sx = outW.toFloat() / framing.outWidth
            val iw = (framing.imageWidth * sx).toInt().coerceAtLeast(2)
            val ih = (framing.imageHeight * sx).toInt().coerceAtLeast(2)
            updateOverlay(framing, outW, outH)
            fr.render(RenderRequest(
                input = input, imageWidth = iw, imageHeight = ih,
                framebuffer = framebuffer, outWidth = outW, outHeight = outH,
                imageRect = framing.imageRect, border = currentSetup?.let { Borders.color(look, it) } ?: floatArrayOf(0f, 0f, 0f),
                overlayTexture = overlayTex,
                original = original, seed = seed, autoEv = autoEv,
            ))
        }

        private fun updateOverlay(framing: Framing, outW: Int, outH: Int) {
            val key = "${look.border}/${look.dateStamp}/${look.film}/${look.format}/$outW/$outH"
            if (key == overlayKey) return
            overlayKey = key
            GlUtil.deleteTexture(overlayTex)
            overlayTex = 0
            if (!OverlayPainter.needsOverlay(look)) return
            val scaled = Framing(framing.crop, framing.imageWidth, framing.imageHeight, outW, outH, framing.imageRect)
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
            st.setOnFrameAvailableListener { newVideoFrame = true; requestRender() }
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
            quad?.release(); quad = null
            print?.release(); print = null
            blit?.release(); blit = null
            GlUtil.deleteTexture(fullTex); fullTex = 0
            pendingFull?.close(); pendingFull = null
            pendingBitmap?.close(); pendingBitmap = null
            currentBitmap?.close(); currentBitmap = null
            GlUtil.deleteTexture(photoTex); photoTex = 0
            GlUtil.deleteTexture(overlayTex); overlayTex = 0
            surfaceTexture?.release(); surfaceTexture = null
            if (oesTex != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
            oesTex = 0
        }
    }
}
