package app.spektralab.gl

import android.opengl.GLES30
import android.opengl.GLES30.*
import android.util.Log
import app.spektralab.raw.BurstMerge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs

/**
 * The gather of the burst merge on the GPU (Shaders.MERGE): the raw frames, their motion,
 * trust masks and the kernels go up as textures, the picture comes back in strips.
 * Anything unexpected — a GPU that cannot do it, a driver error, a result that does not
 * agree with the CPU on a sample — returns null and the caller gathers on the CPU.
 */
object BurstGl {
    private const val TAG = "SpektraBurst"

    fun gather(plan: BurstMerge.Plan, progress: (Float) -> Unit = {}): ShortArray? {
        if (plan.frames.size > Shaders.MERGE_MAX_FRAMES) return null
        var egl: EglCore? = null
        return try {
            egl = EglCore()
            val surface = egl.createPbuffer()
            egl.makeCurrent(surface)
            try { render(plan, progress) } finally { egl.releaseSurface(surface) }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU merge unavailable, using the CPU", t)
            null
        } finally {
            runCatching { egl?.release() }
        }
    }

    private fun texture(target: Int): Int {
        val t = IntArray(1); glGenTextures(1, t, 0)
        glBindTexture(target, t[0])
        // integer and 32-bit float textures cannot be filtered
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun render(plan: BurstMerge.Plan, progress: (Float) -> Unit): ShortArray? {
        val w = plan.width; val h = plan.height
        val n = plan.frames.size; val alts = maxOf(n - 1, 1)
        val limit = IntArray(1)
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, limit, 0)
        if (w > limit[0] || h > limit[0]) return null
        while (glGetError() != GL_NO_ERROR) { /* start clean */ }

        val program = Program(Shaders.VERTEX, Shaders.MERGE)
        val quad = FullscreenQuad()
        val textures = ArrayList<Int>()
        val fbo = IntArray(1)
        try {
            program.use()
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glPixelStorei(GL_PACK_ALIGNMENT, 1)

            // raw frames: the crop area of each, straight from the frame's own array
            glActiveTexture(GL_TEXTURE0)
            textures += texture(GL_TEXTURE_2D_ARRAY)
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R16UI, w, h, n)
            for ((f, img) in plan.frames.withIndex()) {
                glPixelStorei(GL_UNPACK_ROW_LENGTH, img.rawWidth)
                val buf = ShortBuffer.wrap(img.data)
                buf.position(img.cropY * img.rawWidth + img.cropX)
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, f, w, h, 1, GL_RED_INTEGER, GL_UNSIGNED_SHORT, buf)
            }
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
            glUniform1i(program.loc("uRaw"), 0)

            // motion per tile
            val tilesX = plan.flows.firstOrNull()?.tilesX ?: 1; val tilesY = plan.flows.firstOrNull()?.tilesY ?: 1
            glActiveTexture(GL_TEXTURE1)
            textures += texture(GL_TEXTURE_2D_ARRAY)
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_RG32F, tilesX, tilesY, alts)
            for ((f, flow) in plan.flows.withIndex()) {
                val fb = ByteBuffer.allocateDirect(flow.u.size * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in flow.u.indices) { fb.put(flow.u[i]); fb.put(flow.v[i]) }
                fb.position(0)
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, f, tilesX, tilesY, 1, GL_RG, GL_FLOAT, fb)
            }
            glUniform1i(program.loc("uFlow"), 1)

            // trust masks
            glActiveTexture(GL_TEXTURE2)
            textures += texture(GL_TEXTURE_2D_ARRAY)
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R8, plan.guideW, plan.guideH, alts)
            for ((f, mask) in plan.robust.withIndex())
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, f, plan.guideW, plan.guideH, 1, GL_RED, GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask))
            glUniform1i(program.loc("uRobust"), 2)

            // kernels, uploaded in bands to keep the temporary buffer small
            glActiveTexture(GL_TEXTURE3)
            textures += texture(GL_TEXTURE_2D)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGB32F, plan.kernW, plan.kernH)
            val band = 256
            val kb = ByteBuffer.allocateDirect(plan.kernW * band * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var ky = 0
            while (ky < plan.kernH) {
                val rows = minOf(band, plan.kernH - ky)
                kb.position(0)
                for (i in ky * plan.kernW until (ky + rows) * plan.kernW) { kb.put(plan.ka[i]); kb.put(plan.kb[i]); kb.put(plan.kc[i]) }
                kb.position(0)
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, ky, plan.kernW, rows, GL_RGB, GL_FLOAT, kb)
                ky += rows
            }
            glUniform1i(program.loc("uKernel"), 3)

            val flow = plan.flows.firstOrNull()
            glUniform1i(program.loc("uFrames"), n)
            glUniform2i(program.loc("uSize"), w, h)
            glUniform2i(program.loc("uGuide"), plan.guideW, plan.guideH)
            glUniform2i(program.loc("uKern"), plan.kernW, plan.kernH)
            glUniform2i(program.loc("uTiles"), tilesX, tilesY)
            glUniform1f(program.loc("uOrigin"), (flow?.origin ?: 0).toFloat())
            glUniform1f(program.loc("uStride"), (flow?.stride ?: 1).toFloat())
            val cfa = plan.frames[0].cropCfa
            glUniform4i(program.loc("uCfa"), cfa[0], cfa[1], cfa[2], cfa[3])
            val black = FloatArray(Shaders.MERGE_MAX_FRAMES * 4); val scale = FloatArray(Shaders.MERGE_MAX_FRAMES * 4)
            for (f in 0 until n) { plan.frames[f].cropBlack.copyInto(black, f * 4, 0, 4); plan.scale(f).copyInto(scale, f * 4) }
            glUniform4fv(program.loc("uBlack"), Shaders.MERGE_MAX_FRAMES, black, 0)
            glUniform4fv(program.loc("uScale"), Shaders.MERGE_MAX_FRAMES, scale, 0)

            // integer render target: needs no extension in OpenGL ES 3.0
            val strip = (1_000_000 / w).coerceIn(16, 512)
            glActiveTexture(GL_TEXTURE4)
            textures += texture(GL_TEXTURE_2D)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16UI, w, strip)
            glGenFramebuffers(1, fbo, 0)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures.last(), 0)
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return null
            if (glGetError() != GL_NO_ERROR) return null
            glViewport(0, 0, w, strip)
            glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST); glDisable(GL_DITHER)

            val out = ShortArray(w * h * 3)
            val px = ByteBuffer.allocateDirect(w * strip * 16).order(ByteOrder.nativeOrder()).asIntBuffer()
            val row = IntArray(w * 4)
            var y0 = 0
            while (y0 < h) {
                val rows = minOf(strip, h - y0)
                glUniform1i(program.loc("uRow0"), y0)
                quad.draw()
                px.position(0)
                glReadPixels(0, 0, w, rows, GLES30.GL_RGBA_INTEGER, GL_UNSIGNED_INT, px)
                for (r in 0 until rows) {
                    px.position(r * w * 4); px.get(row)
                    var o = ((y0 + r) * w) * 3
                    for (x in 0 until w) { out[o++] = row[x * 4].toShort(); out[o++] = row[x * 4 + 1].toShort(); out[o++] = row[x * 4 + 2].toShort() }
                }
                y0 += rows
                progress(y0.toFloat() / h)
            }
            if (glGetError() != GL_NO_ERROR) return null
            return if (agrees(plan, out)) out else null
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            if (fbo[0] != 0) glDeleteFramebuffers(1, fbo, 0)
            for (t in textures) GlUtil.deleteTexture(t)
            quad.release(); program.release()
        }
    }

    /** Two rows gathered on the CPU must match what the GPU returned (drivers differ; pictures must not). */
    private fun agrees(plan: BurstMerge.Plan, gpu: ShortArray): Boolean {
        val w = plan.width
        var worst = 0.0
        for (y in intArrayOf(plan.height / 3, plan.height * 2 / 3)) {
            val cpu = BurstMerge.gather(plan, y, y + 1)
            var sum = 0L
            for (i in cpu.indices) sum += abs((cpu[i].toInt() and 0xffff) - (gpu[y * w * 3 + i].toInt() and 0xffff))
            worst = maxOf(worst, sum.toDouble() / cpu.size)
        }
        if (worst > 24.0) Log.w(TAG, "GPU merge differs from the CPU by $worst of 65535 on average; discarded")
        return worst <= 24.0
    }
}
