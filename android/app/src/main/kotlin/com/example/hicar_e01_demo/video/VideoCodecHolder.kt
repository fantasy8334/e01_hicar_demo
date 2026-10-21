package com.example.hicar_e01_demo.video

import com.example.hicar_e01_demo.codec.HicarMediaCodec

/**
 * 共享解码器持有者：WiFi 模块创建 MediaCodec 后注入，
 * TextureView 就绪时读取并绑定 Surface。
 */
object VideoCodecHolder {
    @Volatile
    var codec: HicarMediaCodec? = null
}
