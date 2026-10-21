package com.example.hicar_e01_demo.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.example.hicar_e01_demo.HicarLogger

/**
 * 视频 TextureView：Surface 可用时把 Surface 注入共享 MediaCodec。
 * 适配 Android 5.1。
 */
class HicarVideoTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private var surface: Surface? = null

    init {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(st)
                bindSurface()
            }

            override fun onSurfaceTextureSizeChanged(
                st: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                VideoCodecHolder.codec?.setSurface(null)
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            }
        }
    }

    private fun bindSurface() {
        val codec = VideoCodecHolder.codec
        if (codec != null && surface != null) {
            codec.setSurface(surface)
            HicarLogger.log("Surface 已绑定到解码器")
        }
    }

    fun unbind() {
        VideoCodecHolder.codec?.setSurface(null)
        surface?.release()
        surface = null
    }
}
