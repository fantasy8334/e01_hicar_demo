package com.example.hicar_e01_demo

import android.os.Handler
import android.os.Looper
import com.example.hicar_e01_demo.ble.HicarBleServer
import com.example.hicar_e01_demo.wifi.HicarWifiP2pManager

enum class HicarState {
    IDLE,            // 空闲
    BLE_HANDSHAKE,   // BLE 已连接，握手进行中
    WAIT_WIFI,       // 握手完成，建立 WiFi 链路
    STREAM_CONNECTED,// TCP 媒体流连通
    ERROR            // 异常
}

/**
 * 全局链路状态机：统一管理 BLE / WiFi / TCP 联动。
 * 通过构造函数持有 wifiManager，bleServer 由 MainActivity 注入（避免循环依赖）。
 */
class HicarStateMachine(private val wifiManager: HicarWifiP2pManager) {

    var bleServer: HicarBleServer? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var state: HicarState = HicarState.IDLE
        private set

    fun onBleConnected() {
        transition(HicarState.BLE_HANDSHAKE)
    }

    fun onBleHandshakeSuccess() {
        transition(HicarState.WAIT_WIFI)
        HicarLogger.log("BLE 握手成功，自动启动 WiFi P2P")
        mainHandler.post { wifiManager.startHotspot(this) }
    }

    fun onTcpClientConnected() {
        transition(HicarState.STREAM_CONNECTED)
    }

    fun onBleDisconnect() {
        HicarLogger.log("BLE 断开，重置链路")
        resetAll()
    }

    fun onTcpDisconnect() {
        HicarLogger.log("TCP 断开，重置链路")
        resetAll()
    }

    fun onError() {
        HicarLogger.log("链路异常")
        transition(HicarState.ERROR)
        resetAll()
    }

    fun resetAll() {
        mainHandler.post {
            try {
                wifiManager.stop()
            } catch (e: Exception) {
                HicarLogger.log("wifi stop err:${e.message}")
            }
            try {
                bleServer?.resetHandshake()
            } catch (e: Exception) {
                HicarLogger.log("ble reset err:${e.message}")
            }
            transition(HicarState.IDLE)
        }
    }

    private fun transition(newState: HicarState) {
        state = newState
        HicarLogger.log("状态切换 -> $newState")
        MainActivity.notifyState(newState.name)
    }
}
