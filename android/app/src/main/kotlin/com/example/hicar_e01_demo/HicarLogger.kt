package com.example.hicar_e01_demo

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地文件日志模块，E01 车机调试用。
 * 日志写入 {filesDir}/hicar_log.txt
 */
object HicarLogger {

    private var logFile: File? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    @Volatile
    private var enabled = true

    fun init(context: Context) {
        logFile = File(context.filesDir, "hicar_log.txt")
    }

    fun setEnabled(e: Boolean) {
        enabled = e
    }

    @Synchronized
    fun log(msg: String) {
        if (!enabled) return
        val time = sdf.format(Date())
        val line = "[$time] $msg\n"
        try {
            val f = logFile ?: return
            FileWriter(f, true).use { it.write(line) }
        } catch (_: Exception) {
        }
    }
}
