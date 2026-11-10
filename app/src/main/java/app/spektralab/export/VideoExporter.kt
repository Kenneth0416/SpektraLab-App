package app.spektralab.export

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import app.spektralab.data.LibraryItem
import app.spektralab.engine.LookSetup
import app.spektralab.gl.FilmRenderer
import app.spektralab.gl.InputImage
import app.spektralab.gl.RenderRequest
import app.spektralab.render.Framing
import app.spektralab.render.GlWorker
import app.spektralab.render.OverlayPainter
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Re-encodes a video through the film pipeline: MediaCodec decoder ->
 * SurfaceTexture -> [FilmRenderer] (grain regenerated per frame) -> H.264
 * encoder input surface -> MediaMuxer. The audio track is copied unchanged.
 * Runs on the [GlWorker] thread.
 */
class VideoExporter(
    private val context: Context,
    private val worker: GlWorker,
    private val item: LibraryItem,
    private val setup: LookSetup,
    private val maxLongEdge: Int,
    private val output: FileDescriptor,
    private val onProgress: (Float) -> Unit,
    private val cancelled: () -> Boolean,
) {
    fun run() {
        val uri = Uri.parse(item.uri)
        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        val videoTrack = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("no video track")
        extractor.selectTrack(videoTrack)
        val inFormat = extractor.getTrackFormat(videoTrack)
        val rotation = if (inFormat.containsKey(MediaFormat.KEY_ROTATION)) inFormat.getInteger(MediaFormat.KEY_ROTATION) else item.rotation
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else item.durationMs * 1000
        val frameRate = if (inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30) else 30

        val look = setup.look
        // shrink until the device's H.264 encoder accepts the size (portrait 4K is often unsupported)
        val caps = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        }.getOrNull()
        var limit = maxLongEdge
        var framing = Framing.compute(item.width, item.height, look, limit, even = true)
        while (caps != null && limit > 480) {
            val w = framing.outWidth - framing.outWidth % 2
            val h = framing.outHeight - framing.outHeight % 2
            if (caps.isSizeSupported(w, h)) break
            limit = (limit * 0.85).toInt()
            framing = Framing.compute(item.width, item.height, look, limit, even = true)
        }
        val outW = framing.outWidth - framing.outWidth % 2
        val outH = framing.outHeight - framing.outHeight % 2

        // encoder
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (outW.toLong() * outH * frameRate * 0.2).toInt().coerceIn(4_000_000, 80_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encSurface = encoder.createInputSurface()
        encoder.start()

        val egl = worker.egl
        val eglSurface = egl.createWindowSurface(encSurface)
        egl.makeCurrent(eglSurface)
        val renderer: FilmRenderer = worker.renderer
        renderer.setLook(setup)

        // decoder -> SurfaceTexture on this GL context
        val oes = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val cbThread = HandlerThread("spektra-st").apply { start() }
        val lock = ReentrantLock()
        val cond = lock.newCondition()
        var frameReady = false
        val st = SurfaceTexture(oes)
        st.setOnFrameAvailableListener({ lock.withLock { frameReady = true; cond.signalAll() } }, Handler(cbThread.looper))
        val decSurface = Surface(st)
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, decSurface, null, 0)
        decoder.start()

        val overlayBmp = OverlayPainter.paint(framing, look, item.id.toInt() % 36 + 1, item.dateTaken)
        val overlayTex = overlayBmp?.let { GlWorker.uploadRgba(it) } ?: 0
        val stMatrix = FloatArray(16)

        val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxVideo = -1
        var muxAudio = -1
        var muxerStarted = false
        // audio passthrough
        val audioExtractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        val audioTrack = (0 until audioExtractor.trackCount).firstOrNull {
            audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        val audioFormat = audioTrack?.let { audioExtractor.selectTrack(it); audioExtractor.getTrackFormat(it) }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        var frameIndex = 0
        try {
            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) encoder.signalEndOfInputStream()
                var waits = 0
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream || ++waits > 600) return else continue
                    }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxVideo = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null) muxAudio = runCatching { muxer.addTrack(audioFormat) }.getOrDefault(-1)
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (idx < 0) continue
                    val buf = encoder.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(muxVideo, buf, info)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encoderDone = true; return }
                }
            }

            while (!decoderDone) {
                if (cancelled()) throw InterruptedException("cancelled")
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0
                    val pts = info.presentationTimeUs
                    decoder.releaseOutputBuffer(outIdx, render)
                    if (render) {
                        lock.withLock {
                            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500)
                            while (!frameReady) {
                                val left = deadline - System.nanoTime()
                                if (left <= 0) break
                                cond.awaitNanos(left)
                            }
                            frameReady = false
                        }
                        st.updateTexImage()
                        st.getTransformMatrix(stMatrix)
                        val uv = FilmRenderer.multiply(
                            FilmRenderer.multiply(Framing.surfaceTextureTransform(stMatrix), Framing.rotationTransform(rotation)),
                            Framing.cropTransform(framing.crop))
                        renderer.render(RenderRequest(
                            input = InputImage(oes, true, uv),
                            imageWidth = framing.imageWidth, imageHeight = framing.imageHeight,
                            framebuffer = 0, outWidth = outW, outHeight = outH,
                            imageRect = framing.imageRect, overlayTexture = overlayTex,
                            seed = frameIndex * 7919 + 13, autoEv = item.autoEv,
                        ))
                        egl.setPresentationTime(eglSurface, pts * 1000)
                        egl.swap(eglSurface)
                        frameIndex++
                        if (durationUs > 0) onProgress((pts.toFloat() / durationUs).coerceIn(0f, 0.99f))
                        drainEncoder(false)
                    }
                    if (eos) decoderDone = true
                }
            }
            drainEncoder(true)

            if (muxAudio >= 0 && muxerStarted) {
                val buf = ByteBuffer.allocate(1 shl 20)
                val ai = MediaCodec.BufferInfo()
                while (true) {
                    val n = audioExtractor.readSampleData(buf, 0)
                    if (n < 0) break
                    ai.set(0, n, audioExtractor.sampleTime,
                        if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(muxAudio, buf, ai)
                    audioExtractor.advance()
                }
            }
            onProgress(1f)
        } finally {
            runCatching { decoder.stop() }; decoder.release()
            runCatching { encoder.stop() }; encoder.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            extractor.release(); audioExtractor.release()
            decSurface.release(); st.release(); cbThread.quitSafely()
            GLES30.glDeleteTextures(1, intArrayOf(oes), 0)
            if (overlayTex != 0) GLES30.glDeleteTextures(1, intArrayOf(overlayTex), 0)
            egl.releaseSurface(eglSurface)
            encSurface.release()
            renderer.releaseTargets()
        }
        if (!encoderDone) error("encoder did not finish")
    }
}
