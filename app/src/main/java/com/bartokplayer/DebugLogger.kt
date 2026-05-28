package com.bartokplayer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {

    private const val TAG = "BartokPlayer"
    private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5 MB, then rotate
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var logFile: File? = null
    private var writer: PrintWriter? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, "bartok_debug.log")
            openWriter()
            i("Logger initialized — file: ${logFile?.absolutePath}")
            i("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                    "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        }
    }

    fun d(msg: String) = log(Log.DEBUG, msg)
    fun i(msg: String) = log(Log.INFO, msg)
    fun w(msg: String) = log(Log.WARN, msg)
    fun e(msg: String, t: Throwable? = null) {
        log(Log.ERROR, msg)
        t?.let {
            Log.e(TAG, msg, it)
            synchronized(lock) {
                writer?.let { w ->
                    it.printStackTrace(w)
                    w.flush()
                }
            }
        }
    }

    private fun log(level: Int, msg: String) {
        val timestamp = dateFormat.format(Date())
        val levelChar = when (level) {
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> '?'
        }
        val line = "$timestamp [$levelChar] $msg"

        // Logcat
        when (level) {
            Log.DEBUG -> Log.d(TAG, msg)
            Log.INFO -> Log.i(TAG, msg)
            Log.WARN -> Log.w(TAG, msg)
            Log.ERROR -> Log.e(TAG, msg)
        }

        // File
        synchronized(lock) {
            writer?.let {
                it.println(line)
                it.flush()
                rotateIfNeeded()
            }
        }
    }

    private fun openWriter() {
        try {
            writer = PrintWriter(FileWriter(logFile, true), true)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to open log file", ex)
        }
    }

    private fun rotateIfNeeded() {
        val file = logFile ?: return
        if (file.length() > MAX_FILE_SIZE) {
            writer?.close()
            val backup = File(file.parent, "bartok_debug_prev.log")
            backup.delete()
            file.renameTo(backup)
            openWriter()
            writer?.println("${dateFormat.format(Date())} [I] Log rotated, previous log saved as ${backup.name}")
            writer?.flush()
        }
    }

    fun getLogFilePath(): String? = logFile?.absolutePath
}
