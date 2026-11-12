package app.spektralab.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import app.spektralab.R
import app.spektralab.engine.AutoExposure
import app.spektralab.engine.LookSetup
import app.spektralab.gl.EglCore
import app.spektralab.gl.FilmRenderer
import app.spektralab.gl.InputImage
import app.spektralab.gl.RenderRequest
import app.spektralab.gl.RenderTarget
import app.spektralab.render.Framing
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Live film viewfinder: every preview frame of the camera goes through the same GL film
 * pipeline as photos and videos before it reaches the screen. Only the preview stream is
 * processed; captures are saved untouched (raw sensor data when the device offers it).
 */
class FilmViewfinder(context: Context, private val onError: (String) -> Unit) : SurfaceProcessor {
    private val context = context.applicationContext
    private val thread = HandlerThread("spektra-viewfinder").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { r -> if (thread.isAlive) handler.post(r) }

    val effect: CameraEffect = object : CameraEffect(PREVIEW, executor, this, { t -> Log.e(TAG, "effect error", t) }) {}

    // GL thread state
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var renderer: FilmRenderer? = null
    private var setup: LookSetup? = null
    private var oes = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var output: SurfaceOutput? = null
    private var outputEgl: EGLSurface? = null
    private var meter: RenderTarget? = null
    private var autoEv = 0f
    private var frame = 0
    private var failed = false
    private val stMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)

    /**
     * 1 = sharp viewfinder; 0 = the frame has not been advanced yet, so the finder is a
     * ground-glass blur (the pipeline runs on a tiny image and is scaled up).
     */
    @Volatile var clarity = 1f

    /** Camera timestamp of the latest fully blurred buffer submitted to the preview. */
    @Volatile var blurredTimestamp = Long.MIN_VALUE
        private set

    /** Exposure compensation (EV) added to the metered film exposure. */
    @Volatile var evBias = 0f

    /** Tables of the look loaded in the camera. */
    fun setLook(s: LookSetup) = executor.execute { setup = s }

    private fun ensureGl(): Boolean {
        if (failed) return false
        if (renderer != null) return true
        return try {
            val e = EglCore()
            egl = e
            pbuffer = e.createPbuffer()
            e.makeCurrent(pbuffer!!)
            renderer = FilmRenderer()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "viewfinder GL init failed", t)
            failed = true
            onError(context.getString(R.string.gpu_viewfinder_unsupported))
            false
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (!ensureGl()) { request.willNotProvideSurface(); return }
        egl!!.makeCurrent(pbuffer!!)
        if (oes == 0) {
            oes = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        }
        val st = SurfaceTexture(oes)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        st.setOnFrameAvailableListener({ onFrame(it) }, handler)
        val surface = Surface(st)
        surfaceTexture = st
        inputSurface = surface
        request.provideSurface(surface, executor) {
            // the camera is done with this surface
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            if (surfaceTexture === st) { surfaceTexture = null; inputSurface = null }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (!ensureGl()) { surfaceOutput.close(); return }
        val surface = surfaceOutput.getSurface(executor) {
            // output is going away: stop drawing into it
            if (output === surfaceOutput) releaseOutput()
            surfaceOutput.close()
        }
        releaseOutput()
        output = surfaceOutput
        outputEgl = runCatching { egl!!.createWindowSurface(surface) }.getOrNull()
    }

    private fun releaseOutput() {
        val e = egl ?: return
        outputEgl?.let { s ->
            e.makeCurrent(pbuffer!!)
            e.releaseSurface(s)
        }
        outputEgl = null
        output = null
    }

    private fun onFrame(st: SurfaceTexture) {
        val e = egl ?: return
        val r = renderer ?: return
        if (st !== surfaceTexture) return
        val out = output
        val outSurface = outputEgl
        try {
            e.makeCurrent(outSurface ?: pbuffer!!)
            st.updateTexImage()
            val s = setup
            if (out == null || outSurface == null || s == null) return
            st.getTransformMatrix(stMatrix)
            // CameraX folds crop / rotation / mirroring for this output into the matrix
            out.updateTransformMatrix(outMatrix, stMatrix)
            val uv = Framing.surfaceTextureTransform(outMatrix)
            val input = InputImage(oes, true, uv)
            r.setLook(s)
            val w = out.size.width
            val h = out.size.height
            if (frame % METER_EVERY == 0) meter(r, input)
            // process at a bounded size: enough for a viewfinder, and keeps 30 fps within reach
            val frameClarity = clarity.coerceIn(0f, 1f)
            val longEdge = BLUR_LONG_EDGE * (PROCESS_LONG_EDGE.toFloat() / BLUR_LONG_EDGE).pow(frameClarity)
            val scale = minOf(1f, longEdge / max(w, h))
            r.render(RenderRequest(
                input = input,
                imageWidth = (w * scale).roundToInt().coerceAtLeast(2), imageHeight = (h * scale).roundToInt().coerceAtLeast(2),
                framebuffer = 0, outWidth = w, outHeight = h,
                seed = frame * 7919 + 17, autoEv = autoEv + evBias,
            ))
            e.setPresentationTime(outSurface, st.timestamp)
            if (e.swap(outSurface) && frameClarity == 0f) blurredTimestamp = st.timestamp
            frame++
        } catch (t: Throwable) {
            Log.e(TAG, "viewfinder frame failed", t)
            if (!failed) { failed = true; onError(context.getString(R.string.viewfinder_render_failed)) }
        }
    }

    /** spektrafilm's center-weighted metering on a thumbnail of the live frame, smoothed over time. */
    private fun meter(r: FilmRenderer, input: InputImage) {
        val t = meter ?: RenderTarget(METER_W, METER_H, GLES30.GL_RGBA8).also { meter = it }
        r.render(RenderRequest(input = input, imageWidth = METER_W, imageHeight = METER_H,
            framebuffer = t.framebuffer, outWidth = METER_W, outHeight = METER_H, original = true))
        val buf = ByteBuffer.allocateDirect(METER_W * METER_H * 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.framebuffer)
        GLES30.glReadPixels(0, 0, METER_W, METER_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val lin = FloatArray(METER_W * METER_H * 3)
        for (i in 0 until METER_W * METER_H) for (c in 0..2) {
            val v = (buf.get(i * 4 + c).toInt() and 0xff) / 255.0
            lin[i * 3 + c] = (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
        }
        val ev = AutoExposure.centerWeightedEv(lin, METER_W, METER_H).toFloat()
        autoEv = if (frame == 0) ev else autoEv + 0.25f * (ev - autoEv)
    }

    /** Stop the GL thread; call when the camera screen goes away (after unbinding the camera). */
    fun release() {
        handler.post {
            runCatching {
                val out = output
                releaseOutput()
                out?.close()
                surfaceTexture?.let { it.setOnFrameAvailableListener(null); it.release() }
                inputSurface?.release()
                surfaceTexture = null; inputSurface = null
                egl?.let { e ->
                    pbuffer?.let { e.makeCurrent(it) }
                    meter?.release()
                    renderer?.release()
                    if (oes != 0) GLES30.glDeleteTextures(1, intArrayOf(oes), 0)
                    pbuffer?.let { e.releaseSurface(it) }
                    e.release()
                }
            }
            renderer = null; egl = null
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "SpektraViewfinder"
        private const val PROCESS_LONG_EDGE = 1280
        private const val BLUR_LONG_EDGE = 22f
        private const val METER_W = 48
        private const val METER_H = 36
        private const val METER_EVERY = 8
    }
}
