package com.example.hicar_e01_demo.wifi

import com.example.hicar_e01_demo.HicarLogger
import com.example.hicar_e01_demo.HicarStateMachine
import com.example.hicar_e01_demo.MainActivity
import com.example.hicar_e01_demo.codec.HicarMediaCodec
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/**
 * TCP 媒体流服务端，接收手机推来的 H.264 + PCM。
 * 端口 12345，单客户端。
 *
 * 帧头（8B）：[type:2B BE][len:2B BE][crc32:4B BE]
 *   type 0x0001=视频H264, 0x0002=音频PCM
 */
class HicarTcpServer(
    private val mediaCodec: HicarMediaCodec,
    private val stateMachine: HicarStateMachine
) {
    companion object {
        private const val PORT = 12345
        private const val TYPE_VIDEO = 0x0001
        private const val TYPE_AUDIO = 0x0002
        private const val HEAD_SIZE = 8
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val readBuffer = ByteArray(4096)
    private val pkgBuffer = mutableListOf<Byte>()
    private val crc32 = CRC32()

    fun start() {
        if (running.get()) return
        running.set(true)
        Thread {
            try {
                serverSocket = ServerSocket(PORT)
                HicarLogger.log("TCP 服务已启动，端口 $PORT")
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: continue
                    clientSocket = socket
                    HicarLogger.log("手机 TCP 已接入")
                    stateMachine.onTcpClientConnected()
                    handleClient(socket)
                    stateMachine.onTcpDisconnect()
                    clientSocket = null
                }
            } catch (e: Exception) {
                HicarLogger.log("TCP 服务异常:${e.message}")
                stateMachine.onError()
            } finally {
                closeAll()
            }
        }.start()
    }

    private fun handleClient(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        try {
            while (running.get()) {
                val readLen = input.read(readBuffer)
                if (readLen <= 0) break
                pkgBuffer.addAll(readBuffer.take(readLen).toList())
                parsePackage()
            }
        } catch (e: Exception) {
            HicarLogger.log("TCP 读取异常:${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun parsePackage() {
        while (pkgBuffer.size >= HEAD_SIZE) {
            val type = ((pkgBuffer[0].toInt() and 0xFF) shl 8) or (pkgBuffer[1].toInt() and 0xFF)
            val payloadLen = ((pkgBuffer[2].toInt() and 0xFF) shl 8) or (pkgBuffer[3].toInt() and 0xFF)
            val crcIn = ((pkgBuffer[4].toLong() and 0xFF) shl 24) or
                    ((pkgBuffer[5].toLong() and 0xFF) shl 16) or
                    ((pkgBuffer[6].toLong() and 0xFF) shl 8) or
                    (pkgBuffer[7].toLong() and 0xFF)

            if (pkgBuffer.size < HEAD_SIZE + payloadLen) break

            val fullPkt = pkgBuffer.take(HEAD_SIZE + payloadLen)
            pkgBuffer.removeAll(fullPkt)
            val payload = fullPkt.subList(HEAD_SIZE, fullPkt.size).toByteArray()

            crc32.reset()
            crc32.update(payload)
            if (crc32.value != crcIn) {
                HicarLogger.log("CRC 校验失败，丢弃帧 type=$type")
                continue
            }

            val pts = System.currentTimeMillis() * 1000
            when (type) {
                TYPE_VIDEO -> mediaCodec.decodeFrame(payload, pts)
                TYPE_AUDIO -> MainActivity.nativeWritePcm(payload, pts)
                else -> HicarLogger.log("未知 TCP 帧类型: $type")
            }
        }
    }

    fun stop() {
        running.set(false)
        closeAll()
    }

    private fun closeAll() {
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clientSocket = null
        serverSocket = null
    }
}
