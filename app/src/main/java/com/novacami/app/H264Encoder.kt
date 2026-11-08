package com.novacami.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Hardware H.264 encoder fed by a Surface: frames go GPU -> encoder without touching the CPU. */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val sink: (ByteArray, Boolean) -> Unit
) {
    private var codec: MediaCodec? = null
    lateinit var inputSurface: Surface
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var csd: ByteArray? = null
    private val frames = AtomicInteger(0)
    private val bytes = AtomicLong(0)

    fun start() {
        val c = try { create(true) } catch (e: Exception) {
            Log.w(TAG, "CBR not supported, using VBR", e); create(false)
        }
        codec = c
        inputSurface = c.createInputSurface()
        c.start()
        running = true
        worker = Thread({ drain(c) }, "novacami-enc").also { it.start() }
    }

    private fun create(cbr: Boolean): MediaCodec {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
        if (Build.VERSION.SDK_INT >= 23) f.setInteger(MediaFormat.KEY_OPERATING_RATE, fps) // tells the hardware encoder the real frame rate
        f.setInteger("prepend-sps-pps-to-idr-frames", 1)
        if (cbr) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) { c.release(); throw e }
        return c
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx < 0) continue
                val buf = c.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size); buf.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        csd = data
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        sink(if (key) withHeader(data) else data, key)
                        frames.incrementAndGet(); bytes.addAndGet(data.size.toLong())
                    }
                }
                c.releaseOutputBuffer(idx, false)
            } catch (e: IllegalStateException) { break }
        }
    }

    /** Make every keyframe self-contained (SPS/PPS first) so new viewers can start anywhere. */
    private fun withHeader(d: ByteArray): ByteArray {
        val h = csd ?: return d
        val nalType = if (d.size > 4 && d[2].toInt() == 1) d[3].toInt() and 0x1F
                      else if (d.size > 5) d[4].toInt() and 0x1F else 0
        return if (nalType == 7) d else h + d
    }

    fun requestKeyFrame() {
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) { }
    }

    fun setBitrate(bps: Int) {
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) } catch (_: Exception) { }
    }

    /** (frames, bytes) since the last call. */
    fun takeStats(): Pair<Int, Long> = Pair(frames.getAndSet(0), bytes.getAndSet(0))

    fun stop() {
        running = false
        try { worker?.join(500) } catch (_: Exception) { }
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        try { inputSurface.release() } catch (_: Exception) { }
        codec = null
    }

    companion object { private const val TAG = "NovacamiEnc" }
}
