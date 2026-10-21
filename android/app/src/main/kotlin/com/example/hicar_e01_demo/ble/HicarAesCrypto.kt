package com.example.hicar_e01_demo.ble

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CBC 加解密，用于 BLE 握手完成后所有 GATT 业务报文。
 * 加密输出：IV(16B) + 密文
 */
class HicarAesCrypto {

    companion object {
        private const val ALGORITHM = "AES/CBC/PKCS7Padding"
        private const val KEY_SIZE = 16
        private const val IV_SIZE = 16
    }

    private val secureRandom = SecureRandom()
    private var aesKey: ByteArray? = null

    fun setKey(key16: ByteArray) {
        require(key16.size == KEY_SIZE) { "AES key must be 16 bytes" }
        aesKey = key16.copyOf()
    }

    fun clearKey() {
        aesKey = null
    }

    fun hasKey(): Boolean = aesKey != null

    /** 加密：IV(16) + ciphertext */
    fun encrypt(plain: ByteArray): ByteArray? {
        val key = aesKey ?: return null
        return try {
            val iv = ByteArray(IV_SIZE)
            secureRandom.nextBytes(iv)
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val ct = cipher.doFinal(plain)
            iv + ct
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 解密：输入 IV(16) + ciphertext */
    fun decrypt(ivAndCipher: ByteArray): ByteArray? {
        val key = aesKey ?: return null
        if (ivAndCipher.size < IV_SIZE) return null
        return try {
            val iv = ivAndCipher.copyOfRange(0, IV_SIZE)
            val ct = ivAndCipher.copyOfRange(IV_SIZE, ivAndCipher.size)
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(ct)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun generateRandom16(): ByteArray {
        val rand = ByteArray(KEY_SIZE)
        secureRandom.nextBytes(rand)
        return rand
    }

    /** 密钥派生：local XOR peer（演示方案，量产建议 HKDF） */
    fun deriveKey(local: ByteArray, peer: ByteArray): ByteArray {
        require(local.size == KEY_SIZE && peer.size == KEY_SIZE)
        val out = ByteArray(KEY_SIZE)
        for (i in 0 until KEY_SIZE) {
            out[i] = (local[i].toInt() xor peer[i].toInt()).toByte()
        }
        return out
    }
}
