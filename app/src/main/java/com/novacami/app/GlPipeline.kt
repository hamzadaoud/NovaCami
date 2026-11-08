package com.novacami.app

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * All pixel work happens on the GPU:
 *   camera (external texture) -> [optional blur / green / black background using the person mask]
 *   -> hardware encoder surface (+ optional small on-screen preview).
 * The CPU never touches a video frame.
 */
class GlPipeline(private val encoderSurface: Surface, private val outW: Int, private val outH: Int) {

    private val thread = HandlerThread("novacami-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile var mode = 0              // 0 off, 1 blur, 2 green, 3 black
    @Volatile var mirror = false
    // The camera texture matrix gives a picture that is upright for the phone's NATURAL (portrait) orientation.
    // In landscape it must be turned by the display rotation, or it comes out sideways and squashed.
    @Volatile var rotationDegrees = 0   // display rotation, counter-clockwise: 0/90/180/270 (landscape = 90 or 270)
    @Volatile var rotationFix = 0       // manual correction in 90-degree steps (control?rotfix=0/90/180/270)
    @Volatile var previewOn = true
    @Volatile var seg: Segmenter? = null   // receives a small copy of the picture for the person mask
    @Volatile var camW = 0              // size of the camera buffer (set by the engine); used to crop instead of stretch
    @Volatile var camH = 0
    @Volatile var brb = false           // show the "Be right back" card instead of the camera
    @Volatile private var hasBgTex = false
    @Volatile private var hasBrbTex = false
    @Volatile private var bgAspect = 1f
    private var bgTex = 0
    private var brbTex = 0
    private var progImg = 0
    @Volatile var previewFull = true    // portrait: preview shows the whole camera picture (frame marks what the PC gets)
    @Volatile private var released = false

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var encSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var prevSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var prevW = 640
    private var prevH = 360

    private var oesTex = 0
    private var maskTex = 0
    private var blurTex = 0
    private var fbo = 0
    private var progDown = 0
    private var progComp = 0
    private var progSeg = 0
    private var segTex = 0
    private var segFbo = 0
    private var segW = 0
    private var segH = 0
    private var segBuf: ByteBuffer? = null
    private var lastSeg = 0L
    lateinit var surfaceTexture: SurfaceTexture
    lateinit var cameraSurface: Surface
    private val texMat = FloatArray(16)

    private class Mask(val data: ByteArray, val w: Int, val h: Int)
    @Volatile private var pending: Mask? = null
    @Volatile private var hasMask = false

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .also { it.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); it.position(0) }

    // ---------- public API ----------
    fun start() {
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        handler.post {
            try { init() } catch (t: Throwable) { err = t }
            latch.countDown()
        }
        latch.await()
        err?.let { throw RuntimeException("GL init failed: $it", it) }
    }

    /** The user's background picture (null = none). Uploaded on the GL thread. */
    fun setBackgroundBitmap(b: Bitmap?) = uploadPicture(b, true)

    /** The "Be right back" card. */
    fun setBrbBitmap(b: Bitmap?) = uploadPicture(b, false)

    private fun uploadPicture(b: Bitmap?, isBackground: Boolean) {
        handler.post {
            if (released) return@post
            try {
                EGL14.eglMakeCurrent(display, encSurf, encSurf, context)
                if (b == null || b.isRecycled) {
                    if (isBackground) hasBgTex = false else hasBrbTex = false
                } else {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (isBackground) bgTex else brbTex)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    if (isBackground) { bgAspect = b.width.toFloat() / b.height; hasBgTex = true } else hasBrbTex = true
                }
            } catch (e: Exception) { Log.e(TAG, "picture upload", e) }
        }
    }

    fun submitMask(data: ByteArray, w: Int, h: Int) { pending = Mask(data, w, h) }
    fun invalidateMask() { pending = null; hasMask = false }

    fun setPreviewSurface(s: Surface?, w: Int, h: Int) {
        handler.post {
            if (released) return@post
            try {
                EGL14.eglMakeCurrent(display, encSurf, encSurf, context)
                if (prevSurf != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, prevSurf)
                    prevSurf = EGL14.EGL_NO_SURFACE
                }
                if (s != null && s.isValid) {
                    prevSurf = EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)
                    prevW = w; prevH = h
                }
            } catch (e: Exception) { Log.e(TAG, "preview surface", e) }
        }
    }

    fun release() {
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                surfaceTexture.setOnFrameAvailableListener(null)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (prevSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, prevSurf)
                if (encSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encSurf)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            } catch (e: Exception) { Log.e(TAG, "release", e) }
            try { surfaceTexture.release(); cameraSurface.release() } catch (_: Exception) { }
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    // ---------- setup (GL thread) ----------
    private fun init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        encSurf = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, encSurf, encSurf, context)

        val t = IntArray(3)
        GLES20.glGenTextures(3, t, 0)
        oesTex = t[0]; maskTex = t[1]; blurTex = t[2]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        params(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        params(GLES20.GL_TEXTURE_2D)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1))

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex)
        params(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BLUR_W, BLUR_H, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, blurTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        progDown = program(VERT, HEAD + "void main(){ gl_FragColor = vec4(texture2D(uCam, camCoord(vUv)).rgb, 1.0); }")
        progComp = program(VERT, HEAD + COMP)
        progImg = program(VERT, "precision mediump float;\nvarying vec2 vUv;\nuniform sampler2D uImg;\n" +
            "void main(){ gl_FragColor = vec4(texture2D(uImg, vec2(vUv.x, 1.0 - vUv.y)).rgb, 1.0); }")
        val pt = IntArray(2)
        GLES20.glGenTextures(2, pt, 0)
        bgTex = pt[0]; brbTex = pt[1]
        for (id in pt) {   // 1x1 placeholders so the textures are always complete
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            params(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        // same picture as the video (same rotation/mirroring/crop maths), top row first, for the person-mask model
        progSeg = program(VERT_FLIP, HEAD + "void main(){ gl_FragColor = vec4(texture2D(uCam, camCoord(vUv)).rgb, 1.0); }")

        surfaceTexture = SurfaceTexture(oesTex)
        surfaceTexture.setDefaultBufferSize(outW, outH)
        surfaceTexture.setOnFrameAvailableListener({ render() }, handler)
        cameraSurface = Surface(surfaceTexture)
    }

    private fun params(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    // ---------- per frame (GL thread) ----------
    /** True when the phone is upright (portrait), i.e. the picture is 9:16 and the stream is cropped to 16:9. */
    private fun upright(): Boolean = (((rotationDegrees + rotationFix) % 360) + 360) % 360 % 180 == 0

    /** Aspect (w/h) of the whole camera picture as it looks on screen right now (portrait picture = narrower than tall). */
    private fun srcAspect(): Float {
        val outA = outW.toFloat() / outH
        if (camW <= 0 || camH <= 0) return outA
        return if (upright()) camH.toFloat() / camW else camW.toFloat() / camH
    }

    /** How much of the camera picture the stream uses: (1,1) when the shapes match, otherwise a centre-crop (never a stretch). */
    private fun streamCrop(): FloatArray {
        val outA = outW.toFloat() / outH
        val srcA = srcAspect()
        return if (outA > srcA + 0.001f) floatArrayOf(1f, srcA / outA)
               else if (outA < srcA - 0.001f) floatArrayOf(outA / srcA, 1f)
               else floatArrayOf(1f, 1f)
    }

    private fun render() {
        if (released) return
        try {
            EGL14.eglMakeCurrent(display, encSurf, encSurf, context)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMat)
            val showBrb = brb && hasBrbTex
            if (!showBrb) {
                uploadMask()
                val sg = seg
                if (sg != null && sg.wantsFrame()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSeg >= 55) { lastSeg = now; drawSegFrame(sg) }
                }
            }
            val m0 = if (hasMask) mode else 0
            val m = if (m0 == 4 && !hasBgTex) 3 else m0
            GLES20.glViewport(0, 0, outW, outH)
            if (showBrb) {
                drawBrb(0, 0, outW, outH)
            } else {
                if (m == 1) drawBlurSource(-1f, -1f)
                drawComposite(0, 0, outW, outH, m, -1f, -1f, 0f)
            }
            EGLExt.eglPresentationTimeANDROID(display, encSurf, surfaceTexture.timestamp)
            EGL14.eglSwapBuffers(display, encSurf)

            if (previewOn && prevSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, prevSurf, prevSurf, context)
                EGL14.eglSwapInterval(display, 0) // never let the preview slow the stream
                GLES20.glViewport(0, 0, prevW, prevH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                if (!showBrb && previewFull) {
                    // Fill the whole preview with the camera picture (cover). If the stream is a crop of the picture
                    // (phone upright but a 16:9 stream), a frame marks the part the PC receives.
                    val cardAspect = prevW.toFloat() / prevH
                    val srcA = srcAspect()
                    val cx = if (cardAspect >= srcA) 1f else cardAspect / srcA
                    val cy = if (cardAspect >= srcA) srcA / cardAspect else 1f
                    val sc = streamCrop()
                    val band = if (sc[1] < 0.999f) ((sc[1] / 2f) / cy).let { if (it < 0.49f) it else 0f } else 0f
                    if (m == 1) drawBlurSource(cx, cy)
                    drawComposite(0, 0, prevW, prevH, m, cx, cy, band)
                } else {
                    // keep the real aspect ratio: letterbox inside the preview view instead of stretching to it
                    val sc = minOf(prevW.toFloat() / outW, prevH.toFloat() / outH)
                    val vw = (outW * sc).toInt().coerceAtLeast(1)
                    val vh = (outH * sc).toInt().coerceAtLeast(1)
                    if (showBrb) drawBrb((prevW - vw) / 2, (prevH - vh) / 2, vw, vh)
                    else drawComposite((prevW - vw) / 2, (prevH - vh) / 2, vw, vh, m, -1f, -1f, 0f)
                }
                EGL14.eglSwapBuffers(display, prevSurf)
            }
        } catch (e: Exception) {
            Log.e(TAG, "render", e)
        }
    }

    private fun drawBrb(x: Int, y: Int, w: Int, h: Int) {
        GLES20.glViewport(x, y, w, h)
        GLES20.glUseProgram(progImg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, brbTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progImg, "uImg"), 3)
        drawQuad(progImg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Renders the whole upright camera picture at ~256 px and hands it to the segmenter (tiny read-back, ~18 times a second). */
    private fun drawSegFrame(sg: Segmenter) {
        val w = 256
        val h = ((w / srcAspect()).toInt().coerceIn(64, 512)) and 1.inv()
        if (segTex == 0) {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); segTex = t[0]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segTex)
            params(GLES20.GL_TEXTURE_2D)
            val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); segFbo = f[0]
        }
        if (w != segW || h != segH) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, segTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, segFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, segTex, 0)
            segW = w; segH = h
            segBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, segFbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progSeg)
        camUniforms(progSeg, 1f, 1f)          // always the full picture, never a crop
        drawQuad(progSeg)
        val buf = segBuf ?: return
        buf.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        sg.submit(buf, w, h)
    }

    private fun uploadMask() {
        val m = pending ?: return
        pending = null
        val buf = ByteBuffer.allocateDirect(m.data.size)
        buf.put(m.data); buf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, m.w, m.h, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buf)
        hasMask = true
    }

    /** cx/cy < 0 = the stream's own crop; otherwise a custom crop (used by the bigger portrait preview). */
    private fun camUniforms(p: Int, cx: Float, cy: Float) {
        val r = Math.toRadians(((rotationDegrees + rotationFix) % 360).toDouble())
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uTexMat"), 1, false, texMat, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uCos"), Math.cos(r).toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSin"), Math.sin(r).toFloat())
        // Phone held upright: the camera picture is 9:16 but the stream is 16:9. Show the middle band
        // at the right proportions (centre-crop) instead of squashing the whole picture.
        val sc = streamCrop()
        val sx = if (cx >= 0f) cx else sc[0]
        val sy = if (cy >= 0f) cy else sc[1]
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uCrop"), sx, sy)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uCam"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
    }

    private fun drawQuad(p: Int) {
        val a = GLES20.glGetAttribLocation(p, "aPos")
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(a)
    }

    /** Low-resolution copy of the camera; sampling it with a few taps gives a smooth, cheap blur. */
    private fun drawBlurSource(cx: Float, cy: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, BLUR_W, BLUR_H)
        GLES20.glUseProgram(progDown)
        camUniforms(progDown, cx, cy)
        drawQuad(progDown)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawComposite(x: Int, y: Int, w: Int, h: Int, m: Int, cx: Float, cy: Float, band: Float) {
        GLES20.glViewport(x, y, w, h)
        GLES20.glUseProgram(progComp)
        camUniforms(progComp, cx, cy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progComp, "uMask"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progComp, "uBlur"), 2)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progComp, "uMirror"), if (mirror) 1f else 0f)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progComp, "uMode"), m)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progComp, "uBand"), band)
        // user's background picture: cover-fit it over the whole camera picture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progComp, "uBgImg"), 3)
        val srcA = srcAspect()
        val bA = bgAspect
        val ix = if (srcA > bA) 1f else srcA / bA
        val iy = if (srcA > bA) bA / srcA else 1f
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progComp, "uImgCrop"), ix, iy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progComp, "uBlurStep"), 1.5f / BLUR_W, 1.5f / BLUR_H)
        drawQuad(progComp)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    companion object {
        private const val TAG = "NovacamiGL"
        private const val BLUR_W = 256
        private const val BLUR_H = 144

        private const val VERT_FLIP = """
attribute vec2 aPos;
varying vec2 vUv;
void main() { vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0); }"""

        private const val VERT = """
attribute vec2 aPos;
varying vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }"""

        private const val HEAD = """#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUv;
uniform samplerExternalOES uCam;
uniform mat4 uTexMat;
uniform float uCos;
uniform float uSin;
uniform vec2 uCrop;
vec2 camCoord(vec2 uv) {
  // output position -> (crop when the phone is upright) -> position in the natural-orientation camera image (rotate about the centre), then the texture matrix
  vec2 p = (uv - 0.5) * uCrop;
  vec2 q = vec2(uCos * p.x + uSin * p.y, -uSin * p.x + uCos * p.y) + 0.5;
  return (uTexMat * vec4(q, 0.0, 1.0)).xy;
}
"""

        private const val COMP = """
uniform sampler2D uMask;
uniform sampler2D uBlur;
uniform float uMirror;
uniform int uMode;
uniform vec2 uBlurStep;
uniform float uBand;
uniform sampler2D uBgImg;
uniform vec2 uImgCrop;
void main() {
  vec2 uv = vec2(mix(vUv.x, 1.0 - vUv.x, uMirror), vUv.y);
  vec3 c = texture2D(uCam, camCoord(uv)).rgb;
  if (uMode != 0) {
    vec2 mc = (uv - 0.5) * uCrop + 0.5;
    float m = smoothstep(0.35, 0.75, texture2D(uMask, vec2(mc.x, 1.0 - mc.y)).r);
    vec3 bg = vec3(0.0);
    if (uMode == 1) {
      for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
          bg += texture2D(uBlur, uv + vec2(float(i), float(j)) * uBlurStep).rgb;
        }
      }
      bg /= 9.0;
    } else if (uMode == 2) {
      bg = vec3(0.0, 0.694, 0.251);
    } else if (uMode == 4) {
      // position in the whole picture (un-mirrored so the picture itself is never flipped), cover-fitted
      vec2 ic = vec2(mix(mc.x, 1.0 - mc.x, uMirror), mc.y);
      ic = (ic - 0.5) * uImgCrop + 0.5;
      bg = texture2D(uBgImg, vec2(ic.x, 1.0 - ic.y)).rgb;
    }
    c = mix(bg, c, m);
  }
  if (uBand > 0.0) {
    // bigger portrait preview: dim what the PC does not receive and draw a thin frame around what it does
    float d = abs(uv.y - 0.5);
    if (d > uBand) c *= 0.45;
    c = mix(vec3(1.0), c, smoothstep(0.0, 0.0016, abs(d - uBand)));
  }
  gl_FragColor = vec4(c, 1.0);
}"""
    }
}
