package com.example.hicar_e01_demo.video

import android.content.Context
import android.view.View
import io.flutter.plugin.platform.PlatformView

class HicarVideoPlatformView(private val ctx: Context) : PlatformView {

    private val textureView: HicarVideoTextureView = HicarVideoTextureView(ctx)

    override fun getView(): View = textureView

    override fun dispose() {
        VideoCodecHolder.codec?.setSurface(null)
        textureView.unbind()
    }
}
