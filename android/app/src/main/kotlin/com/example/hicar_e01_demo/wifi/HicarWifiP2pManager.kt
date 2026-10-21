package com.example.hicar_e01_demo.wifi

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.example.hicar_e01_demo.HicarLogger
import com.example.hicar_e01_demo.HicarStateMachine
import com.example.hicar_e01_demo.codec.HicarMediaCodec
import com.example.hicar_e01_demo.video.VideoCodecHolder

/**
 * WiFi P2P 管理：优先创建 GO（车机热点）；失败降级 STA（连接手机热点，逻辑预留）。
 * 创建成功后启动 TCP 服务与 MediaCodec。
 */
class HicarWifiP2pManager(private val ctx: Context) {

    private var wifiP2pMgr: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var tcpServer: HicarTcpServer? = null
    private var isGo = false

    fun startHotspot(stateMachine: HicarStateMachine): String {
        try {
            wifiP2pMgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            channel = wifiP2pMgr?.initialize(ctx, Looper.getMainLooper(), null)
        } catch (e: Exception) {
            HicarLogger.log("P2P 初始化失败:${e.message}")
            stateMachine.onError()
            return "P2P 初始化失败"
        }

        val goListener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isGo = true
                HicarLogger.log("P2P GO 创建成功")
                startTcpAndDecoder(stateMachine)
            }

            override fun onFailure(reason: Int) {
                HicarLogger.log("P2P GO 创建失败 reason=$reason，降级 STA")
                startStaMode(stateMachine)
            }
        }
        wifiP2pMgr?.createGroup(channel, goListener)
        return "尝试创建 P2P GO"
    }

    private fun startStaMode(stateMachine: HicarStateMachine) {
        // 预留：连接手机热点。Android5.1 动态连接 WiFi 需要额外 WiFiManager 逻辑。
        // 手机热点 SSID/密码在 BLE 握手时协商下发。
        HicarLogger.log("STA 模式：等待连接手机热点")
        startTcpAndDecoder(stateMachine)
    }

    private fun startTcpAndDecoder(stateMachine: HicarStateMachine) {
        try {
            val codec = HicarMediaCodec()
            codec.init(1280, 720)
            VideoCodecHolder.codec = codec
            tcpServer = HicarTcpServer(codec, stateMachine)
            tcpServer?.start()
        } catch (e: Exception) {
            HicarLogger.log("启动 TCP/解码器失败:${e.message}")
            stateMachine.onError()
        }
    }

    fun stop() {
        try {
            tcpServer?.stop()
        } catch (_: Exception) {
        }
        try {
            VideoCodecHolder.codec?.release()
        } catch (_: Exception) {
        }
        VideoCodecHolder.codec = null
        if (isGo) {
            try {
                wifiP2pMgr?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {}
                })
            } catch (_: Exception) {
            }
        }
        tcpServer = null
        isGo = false
        HicarLogger.log("WiFi P2P 已停止")
    }
}
