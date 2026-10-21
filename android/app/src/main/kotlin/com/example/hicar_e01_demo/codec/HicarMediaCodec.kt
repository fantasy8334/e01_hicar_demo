package com.example.hicar_e01_demo.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * MediaCodec H.264 硬解码器，适配 Android 5.1。
 * Surface 由 TextureView 就绪后注入（可为空时仅解码不渲染，用于冒烟）。
 */
class HicarMediaCodec {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var csd0: ByteBuffer? = null
    private var csd1: ByteBuffer? = null
    private val timeoutUs = 10_000L

    @Volatile
    var width = 1280
        private set
    @Volatile
    var height = 720
        private set

    fun setSurface(s: Surface?) {
        surface = s
    }

    fun init(w: Int = 1280, h: Int = 720) {
        width = w
        height = h
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        csd0?.let { fmt.setByteBuffer("csd-0", it) }
        csd1?.let { fmt.setByteBuffer("csd-1", it) }
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1_048_576)
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec?.configure(fmt, surface, null, 0)
        codec?.start()
    }

    /**
     * 送入一帧 H.264 数据解码渲染。
     */
    fun decodeFrame(data: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        val inIdx = c.dequeueInputBuffer(timeoutUs)
        if (inIdx >= 0) {
            val buf = c.getInputBuffer(inIdx) ?: return
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
        }
        val info = MediaCodec.BufferInfo()
        val outIdx = c.dequeueOutputBuffer(info, timeoutUs)
        if (outIdx >= 0) {
            c.releaseOutputBuffer(outIdx, true)
        }
    }

    fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        surface = null
        csd0 = null
        csd1 = null
    }
}
