package com.example.hicar_e01_demo.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ParcelUuid
import android.content.Context
import android.media.AudioManager
import com.example.hicar_e01_demo.HicarLogger
import com.example.hicar_e01_demo.HicarStateMachine
import com.example.hicar_e01_demo.MainActivity
import java.util.UUID

/**
 * 车机端 BLE GATT Server（作为从机）。
 * 负责：设备发现广播、握手鉴权、AES 密钥协商、控制指令接收。
 * 适配 Android 5.1（仅 BLUETOOTH / BLUETOOTH_ADMIN 权限）。
 */
class HicarBleServer(
    private val ctx: Context,
    private val stateMachine: HicarStateMachine
) {
    companion object {
        private val SERVICE_UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
        private val CHAR_WRITE_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        private val CHAR_NOTIFY_UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        private val DESC_CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // 握手报文（明文）
        const val MSG_HELLO = 0x01
        const val MSG_PEER_RANDOM = 0x02
        const val MSG_ACK = 0x03
        const val MSG_PING = 0x04
        const val MSG_PONG = 0x05
        const val MSG_HANDSHAKE_DONE = 0x06

        // 握手完成后控制指令（AES 加密）
        const val MSG_VOLUME = 0x10
        const val MSG_MUTE = 0x11
        const val MSG_AUDIO_PAUSE = 0x12
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val fragmentAssembler = HicarBleFragmentAssembler()
    private val aesCrypto = HicarAesCrypto()
    private var localRandom: ByteArray? = null

    private val audioManager: AudioManager by lazy {
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun start(): String {
        val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (!adapter.isEnabled) {
            HicarLogger.log("蓝牙未开启")
            return "蓝牙未开启"
        }

        // 检测 BLE 广播能力（部分 E01 硬件不支持外设广播）
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            HicarLogger.log("硬件不支持BLE广播")
            return "硬件不支持BLE广播"
        }

        gattServer = btManager.openGattServer(ctx, gattCallback)
        val service = android.bluetooth.BluetoothGattService(
            SERVICE_UUID,
            android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val writeChar = BluetoothGattCharacteristic(
            CHAR_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        notifyChar = BluetoothGattCharacteristic(
            CHAR_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            DESC_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyChar?.addDescriptor(cccd)
        service.addCharacteristic(writeChar)
        notifyChar?.let { service.addCharacteristic(it) }
        gattServer?.addService(service)

        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(advSettings, advData, advCallback)

        localRandom = aesCrypto.generateRandom16()
        HicarLogger.log("BLE 服务启动，开始广播")
        return "BLE广播启动成功"
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(advCallback)
        } catch (_: Exception) {
        }
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        advertiser = null
        gattServer = null
        aesCrypto.clearKey()
        localRandom = null
        fragmentAssembler.reset()
        HicarLogger.log("BLE 服务已停止")
    }

    fun resetHandshake() {
        aesCrypto.clearKey()
        localRandom = null
        fragmentAssembler.reset()
        HicarLogger.log("BLE 握手状态重置")
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: android.bluetooth.BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                HicarLogger.log("手机 GATT 已连接")
                stateMachine.onBleConnected()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                HicarLogger.log("手机 GATT 断开")
                stateMachine.onBleDisconnect()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device, requestId, characteristic,
                preparedWrite, responseNeeded, offset, value
            )
            value?.let { handlePacket(device, it) }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun handlePacket(device: android.bluetooth.BluetoothDevice, raw: ByteArray) {
        val fullPkt = fragmentAssembler.inputFragment(raw) ?: return

        val plain: ByteArray = if (aesCrypto.hasKey()) {
            aesCrypto.decrypt(fullPkt) ?: return
        } else {
            fullPkt
        }

        // 握手阶段：msgType(1)+len(2)+payload
        // 握手完成后：msgType(1)+seq(2)+len(2)+payload
        var msgType: Int
        var seq = 0
        var payloadLen: Int
        var payload: ByteArray

        if (!aesCrypto.hasKey()) {
            if (plain.size < 3) return
            msgType = plain[0].toInt() and 0xFF
            payloadLen = ((plain[1].toInt() and 0xFF) shl 8) or (plain[2].toInt() and 0xFF)
            if (plain.size < 3 + payloadLen) return
            payload = plain.copyOfRange(3, 3 + payloadLen)
        } else {
            if (plain.size < 5) return
            msgType = plain[0].toInt() and 0xFF
            seq = ((plain[1].toInt() and 0xFF) shl 8) or (plain[2].toInt() and 0xFF)
            payloadLen = ((plain[3].toInt() and 0xFF) shl 8) or (plain[4].toInt() and 0xFF)
            if (plain.size < 5 + payloadLen) return
            payload = plain.copyOfRange(5, 5 + payloadLen)
        }

        when (msgType) {
            MSG_HELLO -> {
                localRandom = aesCrypto.generateRandom16()
                val resp = buildPacket(MSG_PEER_RANDOM, 0, localRandom!!)
                sendNotify(device, resp)
                HicarLogger.log("收到 HELLO，发送车机随机数")
            }
            MSG_PEER_RANDOM -> {
                val peerRand = payload.copyOfRange(0, 16)
                val key = aesCrypto.deriveKey(localRandom ?: ByteArray(16), peerRand)
                aesCrypto.setKey(key)
                val done = buildPacket(MSG_HANDSHAKE_DONE, 0, ByteArray(0))
                sendNotify(device, done)
                stateMachine.onBleHandshakeSuccess()
                HicarLogger.log("密钥派生完成，握手成功")
            }
            MSG_HANDSHAKE_DONE -> {
                stateMachine.onBleHandshakeSuccess()
            }
            MSG_PING -> {
                val pong = buildPacket(MSG_PONG, seq, ByteArray(0))
                sendNotify(device, pong)
                HicarLogger.log("心跳 PING -> PONG")
            }
            MSG_ACK -> {
                HicarLogger.log("收到 ACK seq=$seq")
            }
            MSG_VOLUME -> {
                if (payload.isNotEmpty()) {
                    val vol = payload[0].toInt() and 0xFF
                    setVolume(vol)
                    sendAck(device, MSG_VOLUME, seq)
                }
            }
            MSG_MUTE -> {
                if (payload.isNotEmpty()) {
                    setMute(payload[0].toInt() != 0)
                    sendAck(device, MSG_MUTE, seq)
                }
            }
            MSG_AUDIO_PAUSE -> {
                if (payload.isNotEmpty()) {
                    setPause(payload[0].toInt() != 0)
                    sendAck(device, MSG_AUDIO_PAUSE, seq)
                }
            }
        }
    }

    private fun sendAck(device: android.bluetooth.BluetoothDevice, ackType: Int, seq: Int) {
        val ackPayload = byteArrayOf(
            ackType.toByte(),
            (seq shr 8).toByte(),
            (seq and 0xFF).toByte(),
            0x00.toByte()
        )
        val ackPkt = buildPacket(MSG_ACK, seq, ackPayload)
        sendNotify(device, ackPkt)
    }

    /**
     * 组装报文：握手阶段不带 seq（明文）；握手完成后带 seq（AES 加密）。
     */
    private fun buildPacket(msgType: Int, seq: Int, payload: ByteArray): ByteArray {
        return if (!aesCrypto.hasKey()) {
            val pkg = ByteArray(3 + payload.size)
            pkg[0] = msgType.toByte()
            pkg[1] = (payload.size shr 8).toByte()
            pkg[2] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, pkg, 3, payload.size)
            pkg
        } else {
            val pkg = ByteArray(5 + payload.size)
            pkg[0] = msgType.toByte()
            pkg[1] = (seq shr 8).toByte()
            pkg[2] = (seq and 0xFF).toByte()
            pkg[3] = (payload.size shr 8).toByte()
            pkg[4] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, pkg, 5, payload.size)
            aesCrypto.encrypt(pkg) ?: pkg
        }
    }

    private fun sendNotify(device: android.bluetooth.BluetoothDevice, rawPayload: ByteArray) {
        val pktList = fragmentAssembler.splitPacket(rawPayload)
        val char = notifyChar ?: return
        pktList.forEach { pkt ->
            char.value = pkt
            gattServer?.notifyCharacteristicChanged(device, char, false)
        }
    }

    // ---------- 音频控制（Kotlin AudioManager） ----------
    private fun setVolume(vol: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (vol.toFloat() / 100 * max).toInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        HicarLogger.log("设置音量 $vol% -> 系统 $target")
    }

    private fun setMute(mute: Boolean) {
        @Suppress("DEPRECATION")
        audioManager.setStreamMute(AudioManager.STREAM_MUSIC, mute)
        HicarLogger.log("静音: $mute")
    }

    private fun setPause(pause: Boolean) {
        MainActivity.nativeAudioControl(2, if (pause) 0 else 1)
        HicarLogger.log("音频暂停: $pause")
    }

    private val advCallback = object : AdvertiseCallback() {}
}
