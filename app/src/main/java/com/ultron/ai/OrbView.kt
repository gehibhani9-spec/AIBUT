package com.ultron.ai

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Rotating wireframe 3D orb that changes colour and pulse with Ultron's state. */
class OrbView(c: Context) : GLSurfaceView(c) {
    init {
        setEGLContextClientVersion(2)
        setRenderer(R())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private class R : GLSurfaceView.Renderer {
        private var prog = 0
        private var uM = 0
        private var uCol = 0
        private var count = 0
        private lateinit var vb: FloatBuffer
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val tmp = FloatArray(16)
        private val mvp = FloatArray(16)
        private val t0 = System.nanoTime()
        private var cr = 0.8f; private var cg = 0.15f; private var cb = 0.15f
        private var speed = 20f

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            val vs = "attribute vec4 p; uniform mat4 m; varying float d;" +
                "void main(){ gl_Position = m * p; d = p.z; }"
            val fs = "precision mediump float; uniform vec4 col; varying float d;" +
                "void main(){ gl_FragColor = vec4(col.rgb * (0.55 + 0.45 * d), 1.0); }"
            prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(prog)
            uM = GLES20.glGetUniformLocation(prog, "m")
            uCol = GLES20.glGetUniformLocation(prog, "col")

            val v = ArrayList<Float>()
            fun pt(x: Double, y: Double, z: Double) { v.add(x.toFloat()); v.add(y.toFloat()); v.add(z.toFloat()) }
            val n = 64
            var lat = -75
            while (lat <= 75) {
                val phi = Math.toRadians(lat.toDouble())
                val rr = cos(phi); val y = sin(phi)
                for (i in 0 until n) {
                    val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n
                    pt(rr * cos(a0), y, rr * sin(a0)); pt(rr * cos(a1), y, rr * sin(a1))
                }
                lat += 15
            }
            for (lon in 0 until 12) {
                val th = PI * lon / 12
                for (i in 0 until n) {
                    val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n
                    pt(cos(a0) * cos(th), sin(a0), cos(a0) * sin(th))
                    pt(cos(a1) * cos(th), sin(a1), cos(a1) * sin(th))
                }
            }
            count = v.size / 3
            vb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(v.toFloatArray()); vb.position(0)
            Matrix.setLookAtM(view, 0, 0f, 0f, 3.6f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            Matrix.perspectiveM(proj, 0, 45f, w.toFloat() / h.toFloat(), 0.1f, 10f)
        }

        private fun drawSphere(scale: Float, angle: Float) {
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, angle, 0.3f, 1f, 0f)
            Matrix.scaleM(model, 0, scale, scale, scale)
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
            GLES20.glUniformMatrix4fv(uM, 1, false, mvp, 0)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, count)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val t = (System.nanoTime() - t0) / 1e9f
            val tr: FloatArray; val amp: Float; val freq: Float
            when (UltronState.mode) {
                Mode.IDLE -> { tr = floatArrayOf(0.8f, 0.15f, 0.15f, 20f); amp = 0.02f; freq = 1.5f }
                Mode.LISTENING -> { tr = floatArrayOf(0.1f, 0.9f, 0.9f, 45f); amp = 0.06f; freq = 5f }
                Mode.THINKING -> { tr = floatArrayOf(1f, 0.7f, 0.1f, 140f); amp = 0.04f; freq = 9f }
                Mode.SPEAKING -> { tr = floatArrayOf(0.95f, 0.3f, 0.9f, 60f); amp = 0.10f; freq = 7f }
            }
            cr += (tr[0] - cr) * 0.08f; cg += (tr[1] - cg) * 0.08f; cb += (tr[2] - cb) * 0.08f
            speed += (tr[3] - speed) * 0.05f
            GLES20.glUseProgram(prog)
            GLES20.glUniform4f(uCol, cr, cg, cb, 1f)
            val loc = GLES20.glGetAttribLocation(prog, "p")
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, 3, GLES20.GL_FLOAT, false, 12, vb)
            GLES20.glLineWidth(2f)
            val pulse = 1f + amp * sin(t * freq)
            drawSphere(pulse, t * speed)
            drawSphere(0.55f * pulse, -t * speed * 1.7f)
            GLES20.glDisableVertexAttribArray(loc)
        }
    }
}
