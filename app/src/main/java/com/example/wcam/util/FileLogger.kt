package com.example.wcam.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {
    private const val TAG = "wCam_FileLogger"
    private var logDir: File? = null

    fun init(context: Context) {
        try {
            // Use app's external files directory by default to avoid Scoped Storage EPERM
            val dir = File(context.getExternalFilesDir(null), "log")
            if (!dir.exists()) dir.mkdirs()
            logDir = dir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log directory", e)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        saveToFile("ERROR", tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        saveToFile("WARN", tag, message, throwable)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    private fun saveToFile(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return
        
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val logFile = File(dir, "error_$dateStr.log")
            
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val logLine = "[$timeStr] $level/$tag: $message\n"
            
            FileOutputStream(logFile, true).use { fos ->
                fos.write(logLine.toByteArray())
                throwable?.let {
                    val pw = PrintWriter(fos)
                    it.printStackTrace(pw)
                    pw.flush()
                }
                fos.write("\n".toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }
}
