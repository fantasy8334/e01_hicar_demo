package com.example.hicar_e01_demo

import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.example.hicar_e01_demo.ble.HicarBleServer
import com.example.hicar_e01_demo.wifi.HicarWifiP2pManager
import com.example.hicar_e01_demo.video.HicarVideoViewFactory

class MainActivity : FlutterActivity() {
    companion object {
        const val CHANNEL = "com.example.hicar_e01_demo/native"

        @Volatile
        var methodChannel: MethodChannel? = null

        // JNI native 方法（静态，对应 native_lib.cpp 的 Java_..._MainActivity_*）
        @JvmStatic
        external fun nativeInitAudio(): Int

        @JvmStatic
        external fun nativeWritePcm(data: ByteArray, ptsUs: Long): Int

        @JvmStatic
        external fun nativeAudioControl(cmd: Int, value: Int): Int

        @JvmStatic
        external fun nativeReleaseAudio(): Int

        init {
            System.loadLibrary("native_lib")
        }

        fun notifyState(stateName: String) {
            methodChannel?.invokeMethod("onStateChange", stateName)
        }
    }

    private lateinit var bleServer: HicarBleServer
    private lateinit var wifiManager: HicarWifiP2pManager
    private lateinit var stateMachine: HicarStateMachine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HicarLogger.init(applicationContext)
        HicarLogger.log("APP onCreate")
        // 初始化 OpenSL ES 音频底层
        val ret = nativeInitAudio()
        HicarLogger.log("nativeInitAudio=$ret")
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // 注册原生视频 PlatformView
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "hicar_video_view",
            HicarVideoViewFactory()
        )

        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        )

        // 组装模块（解决循环依赖：stateMachine 先持有 wifiManager，再注入 bleServer）
        wifiManager = HicarWifiP2pManager(this)
        stateMachine = HicarStateMachine(wifiManager)
        bleServer = HicarBleServer(this, stateMachine)
        stateMachine.bleServer = bleServer

        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "startBleServer" -> {
                    val ret = bleServer.start()
                    HicarLogger.log("调用:startBleServer -> $ret")
                    result.success(ret)
                }
                "stopBleServer" -> {
                    bleServer.stop()
                    HicarLogger.log("调用:stopBleServer")
                    result.success("stopped")
                }
                "startWifiP2p" -> {
                    val ret = wifiManager.startHotspot(stateMachine)
                    HicarLogger.log("调用:startWifiP2p -> $ret")
                    result.success(ret)
                }
                "stopWifiP2p" -> {
                    wifiManager.stop()
                    HicarLogger.log("调用:stopWifiP2p")
                    result.success("stopped")
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        HicarLogger.log("APP onPause")
        nativeAudioControl(2, 0) // 暂停音频
    }

    override fun onResume() {
        super.onResume()
        HicarLogger.log("APP onResume")
        nativeAudioControl(2, 1) // 恢复音频
    }

    override fun onDestroy() {
        HicarLogger.log("APP onDestroy，释放资源")
        try {
            wifiManager.stop()
        } catch (e: Exception) {
            HicarLogger.log("wifi stop err:${e.message}")
        }
        try {
            bleServer.stop()
        } catch (e: Exception) {
            HicarLogger.log("ble stop err:${e.message}")
        }
        try {
            nativeReleaseAudio()
        } catch (e: Exception) {
            HicarLogger.log("audio release err:${e.message}")
        }
        HicarLogger.log("资源释放完成")
        super.onDestroy()
    }
}
