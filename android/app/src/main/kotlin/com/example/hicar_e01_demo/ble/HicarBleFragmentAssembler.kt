package com.example.hicar_e01_demo.ble

/**
 * BLE GATT 报文分片 / 重组。
 * BLE 默认单包 payload 约 20 字节，超过需分片。
 *
 * 分片包结构：`[0xAA][分片序号:1B][总分片数:1B][完整报文总长:2B][payload…]`
 */
class HicarBleFragmentAssembler {

    companion object {
        private const val FRAME_HEAD: Byte = 0xAA.toByte()
        private const val HEAD_SIZE = 5
        private const val MAX_FRAG_PAYLOAD = 15 // 20 - 5 头
        private const val MAX_PACKET = 1024
        private const val TIMEOUT_MS = 300L
    }

    private val fragmentBuffer = mutableMapOf<Int, ByteArray>()
    private var totalFragments = 0
    private var fullPacketLen = 0
    private var lastRecvTimeMs = 0L

    /**
     * 送入一个 BLE 分片包，收齐后返回完整报文；否则返回 null。
     */
    @Synchronized
    fun inputFragment(pkt: ByteArray): ByteArray? {
        val now = System.currentTimeMillis()
        if (now - lastRecvTimeMs > TIMEOUT_MS) {
            reset()
        }
        lastRecvTimeMs = now

        if (pkt.size < HEAD_SIZE) return null
        if (pkt[0] != FRAME_HEAD) {
            reset()
            return null
        }

        val fragIndex = pkt[1].toInt() and 0xFF
        val fragTotal = pkt[2].toInt() and 0xFF
        val pkgLen = ((pkt[3].toInt() and 0xFF) shl 8) or (pkt[4].toInt() and 0xFF)

        if (totalFragments == 0) {
            totalFragments = fragTotal
            fullPacketLen = pkgLen
        } else if (totalFragments != fragTotal || fullPacketLen != pkgLen) {
            reset()
            return null
        }

        if (fragIndex !in 0 until totalFragments) return null
        val payload = pkt.copyOfRange(HEAD_SIZE, pkt.size)
        fragmentBuffer[fragIndex] = payload

        if (fragmentBuffer.size == totalFragments) {
            val full = assemble()
            reset()
            return full
        }
        return null
    }

    private fun assemble(): ByteArray? {
        if (fullPacketLen > MAX_PACKET) return null
        val out = ByteArray(fullPacketLen)
        var pos = 0
        for (i in 0 until totalFragments) {
            val seg = fragmentBuffer[i] ?: return null
            if (pos + seg.size > out.size) return null
            System.arraycopy(seg, 0, out, pos, seg.size)
            pos += seg.size
        }
        return if (pos == fullPacketLen) out else null
    }

    /**
     * 发送侧：把完整报文切分为 BLE 分片包列表。
     */
    fun splitPacket(rawData: ByteArray): List<ByteArray> {
        if (rawData.size > MAX_PACKET) {
            throw IllegalArgumentException("packet too large: ${rawData.size}")
        }
        val total = (rawData.size + MAX_FRAG_PAYLOAD - 1) / MAX_FRAG_PAYLOAD
        if (total == 0) return emptyList()

        val list = mutableListOf<ByteArray>()
        var offset = 0
        for (fragIdx in 0 until total) {
            val remain = rawData.size - offset
            val segLen = if (remain > MAX_FRAG_PAYLOAD) MAX_FRAG_PAYLOAD else remain
            val pkg = ByteArray(HEAD_SIZE + segLen)
            pkg[0] = FRAME_HEAD
            pkg[1] = fragIdx.toByte()
            pkg[2] = total.toByte()
            pkg[3] = (rawData.size shr 8).toByte()
            pkg[4] = (rawData.size and 0xFF).toByte()
            System.arraycopy(rawData, offset, pkg, HEAD_SIZE, segLen)
            offset += segLen
            list.add(pkg)
        }
        return list
    }

    @Synchronized
    fun reset() {
        fragmentBuffer.clear()
        totalFragments = 0
        fullPacketLen = 0
    }
}
