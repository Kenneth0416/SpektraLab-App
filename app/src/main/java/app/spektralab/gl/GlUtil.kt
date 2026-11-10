package app.spektralab.gl

import android.opengl.GLES30
import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

object GlUtil {
    private const val TAG = "SpektraGL"

    fun program(vertex: String, fragment: String): Int {
        val vs = shader(GL_VERTEX_SHADER, vertex)
        val fs = shader(GL_FRAGMENT_SHADER, fragment)
        val p = glCreateProgram()
        glAttachShader(p, vs)
        glAttachShader(p, fs)
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetProgramInfoLog(p)
            glDeleteProgram(p)
            error("link failed: $log")
        }
        glDeleteShader(vs)
        glDeleteShader(fs)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e(TAG, "shader compile failed: $log\n$src")
            glDeleteShader(s)
            error("shader compile failed: $log")
        }
        return s
    }

    fun checkError(where: String) {
        val e = glGetError()
        if (e != GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
    }

    fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    /** Float data -> half-float buffer (for RGB16F / RGBA16F uploads). */
    @android.annotation.SuppressLint("HalfFloat")
    fun halfBuffer(a: FloatArray, channelsIn: Int, channelsOut: Int): ShortBuffer {
        val n = a.size / channelsIn
        val b = ByteBuffer.allocateDirect(n * channelsOut * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until n) for (c in 0 until channelsOut) {
            val v = if (c < channelsIn) a[i * channelsIn + c] else 1f
            b.put(android.util.Half.toHalf(v))
        }
        b.position(0)
        return b
    }

    fun texture2d(): Int {
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return t[0]
    }

    /** RGB float table uploaded as an RGBA16F texture of width x height texels. */
    fun uploadHalfTexture(tex: Int, data: FloatArray, width: Int, height: Int, channels: Int) {
        glBindTexture(GL_TEXTURE_2D, tex)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT,
            halfBuffer(data, channels, 4))
    }

    fun deleteTexture(t: Int) { if (t != 0) glDeleteTextures(1, intArrayOf(t), 0) }

    val maxTextureSize: Int by lazy { IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0] }
}

/** Offscreen render target with a single colour texture. */
class RenderTarget(val width: Int, val height: Int, val internalFormat: Int = GLES30.GL_RGBA16F) {
    val texture: Int = GlUtil.texture2d()
    val framebuffer: Int

    init {
        val (format, type) = when (internalFormat) {
            GL_RGBA16F -> GL_RGBA to GL_HALF_FLOAT
            else -> GL_RGBA to GL_UNSIGNED_BYTE
        }
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, null)
        val fb = IntArray(1)
        glGenFramebuffers(1, fb, 0)
        framebuffer = fb[0]
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        check(status == GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete 0x${Integer.toHexString(status)} (${width}x$height)" }
    }

    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glViewport(0, 0, width, height)
    }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GlUtil.deleteTexture(texture)
    }
}

/** Shared full-screen triangle strip. */
class FullscreenQuad {
    private val vao: Int
    private val vbo: Int

    init {
        val a = IntArray(1)
        glGenVertexArrays(1, a, 0); vao = a[0]
        glGenBuffers(1, a, 0); vbo = a[0]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        glBufferData(GL_ARRAY_BUFFER, verts.size * 4, GlUtil.floatBuffer(verts), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
        glBindVertexArray(0)
    }

    fun draw() {
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBindVertexArray(0)
    }

    fun release() {
        glDeleteBuffers(1, intArrayOf(vbo), 0)
        glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }
}

/** Thin wrapper caching uniform locations. */
class Program(vertex: String, fragment: String) {
    val id = GlUtil.program(vertex, fragment)
    private val locations = HashMap<String, Int>()

    fun use() = glUseProgram(id)
    fun loc(name: String): Int = locations.getOrPut(name) { glGetUniformLocation(id, name) }
    fun set1i(n: String, v: Int) = glUniform1i(loc(n), v)
    fun set1ui(n: String, v: Int) = GLES30.glUniform1ui(loc(n), v)
    fun set1f(n: String, v: Float) = glUniform1f(loc(n), v)
    fun set2f(n: String, a: Float, b: Float) = glUniform2f(loc(n), a, b)
    fun set3f(n: String, a: Float, b: Float, c: Float) = glUniform3f(loc(n), a, b, c)
    fun set3fv(n: String, v: FloatArray) = glUniform3fv(loc(n), v.size / 3, v, 0)
    fun set4f(n: String, a: Float, b: Float, c: Float, d: Float) = glUniform4f(loc(n), a, b, c, d)
    fun set1fv(n: String, v: FloatArray) = glUniform1fv(loc(n), v.size, v, 0)
    /** Row-major 3x3 matrix. */
    fun setMat3RowMajor(n: String, m: FloatArray) = glUniformMatrix3fv(loc(n), 1, true, m, 0)
    fun setMat3ColumnMajor(n: String, m: FloatArray) = glUniformMatrix3fv(loc(n), 1, false, m, 0)

    fun texture(n: String, unit: Int, tex: Int, target: Int = GL_TEXTURE_2D) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(target, tex)
        glUniform1i(loc(n), unit)
    }

    fun release() = glDeleteProgram(id)
}
