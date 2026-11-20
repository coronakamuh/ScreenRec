package com.screenrec.app

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Menerima gambar layar penuh dari VirtualDisplay lalu menggambar HANYA area [crop]
 * ke permukaan input encoder (OpenGL ES 2.0).
 */
class RegionRenderer(
    private val encoderSurface: Surface,
    private val srcW: Int,
    private val srcH: Int,
    private val crop: Rect,
    private val outW: Int,
    private val outH: Int,
) {
    private val thread = HandlerThread("rec-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var texId = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMat = 0
    private var uSampler = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    private lateinit var posBuf: FloatBuffer
    private lateinit var texBuf: FloatBuffer

    @Volatile
    private var released = false

    /** Mengembalikan Surface yang harus diberikan ke VirtualDisplay. */
    fun start(): Surface {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                init()
            } catch (t: Throwable) {
                error = t
            }
            latch.countDown()
        }
        latch.await()
        error?.let { throw RuntimeException("Gagal menyiapkan OpenGL", it) }
        return inputSurface!!
    }

    private fun init() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "EGL display tidak ada" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize gagal" }

        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig gagal"
        }

        eglContext = EGL14.eglCreateContext(
            eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, cfgs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent gagal" }

        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMat = GLES20.glGetUniformLocation(program, "uMat")
        uSampler = GLES20.glGetUniformLocation(program, "sTex")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Kotak (strip) menutupi seluruh output; koordinat tekstur = area yang dipotong
        posBuf = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        val u0 = crop.left.toFloat() / srcW
        val u1 = crop.right.toFloat() / srcW
        val v0 = 1f - crop.bottom.toFloat() / srcH
        val v1 = 1f - crop.top.toFloat() / srcH
        texBuf = floatBuf(floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1))

        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(srcW, srcH)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun drawFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uSampler, 0)
            GLES20.glUniformMatrix4fv(uMat, 1, false, texMatrix, 0)

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, posBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, texBuf)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            Log.w(TAG, "drawFrame gagal", e)
        }
    }

    /** Hentikan dan bebaskan semua sumber daya. Aman dipanggil dari thread mana pun. */
    fun stop() {
        val latch = CountDownLatch(1)
        handler.post {
            released = true
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                inputSurface?.release()
                surfaceTexture?.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (e: Exception) {
                Log.w(TAG, "release gagal", e)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            surfaceTexture = null
            inputSurface = null
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a)
            position(0)
        }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Link program gagal: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Compile shader gagal: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        private const val TAG = "RegionRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX = """
            uniform mat4 uMat;
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uMat * vec4(aTex, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """
    }
}
