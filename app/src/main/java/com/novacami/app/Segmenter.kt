package com.novacami.app

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Person-mask producer. It no longer reads its own camera stream: the GPU hands it a small copy of the
 * exact picture it is drawing (upright, same crop and mirroring), so the mask always lines up with the video.
 * It works on a ~256 px picture, at most ~18 times/second, and does nothing while effects are off.
 */
class Segmenter(private val exec: Executor) {
    @Volatile var enabled = false
    var onMask: ((ByteArray, Int, Int) -> Unit)? = null
    private val busy = AtomicBoolean(false)
    private var bmp: Bitmap? = null

    private val client = Segmentation.getClient(
        SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()
    )

    /** True when the GPU should send the next picture (effects on and the last one has been processed). */
    fun wantsFrame(): Boolean = enabled && !busy.get()

    /** [rgba]: w*h*4 bytes, first row = top of the picture. Called on the GL thread. */
    fun submit(rgba: ByteBuffer, w: Int, h: Int) {
        if (!busy.compareAndSet(false, true)) return
        try {
            var b = bmp
            if (b == null || b.width != w || b.height != h) {
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp = b
            }
            rgba.position(0)
            b!!.copyPixelsFromBuffer(rgba)
            client.process(InputImage.fromBitmap(b, 0))
                .addOnSuccessListener(exec) { mask ->
                    try {
                        val mw = mask.width
                        val mh = mask.height
                        val fb = mask.buffer.also { it.rewind() }.asFloatBuffer()
                        val out = ByteArray(mw * mh)
                        for (i in out.indices) out[i] = (fb.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte()
                        onMask?.invoke(out, mw, mh)
                    } catch (e: Exception) {
                        Log.e(TAG, "mask", e)
                    } finally {
                        busy.set(false)
                    }
                }
                .addOnFailureListener(exec) { e ->
                    Log.e(TAG, "segmentation failed", e)
                    busy.set(false)
                }
        } catch (e: Exception) {
            Log.e(TAG, "submit", e)
            busy.set(false)
        }
    }

    companion object { private const val TAG = "Novacami" }
}
