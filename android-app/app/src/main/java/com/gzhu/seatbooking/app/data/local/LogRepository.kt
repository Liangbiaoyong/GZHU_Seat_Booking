package com.gzhu.seatbooking.app.data.local

import android.content.Context
import android.os.Environment
import android.util.Log
import com.gzhu.seatbooking.app.data.model.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LogRepository(private val context: Context) {
    private companion object {
        private const val TAG = "LogRepository"
    }

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val exportNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val lock = Any()

    private fun logDir(): File = File(context.filesDir, "logs").apply { mkdirs() }
    private fun exportDir(): File = resolveExportDir().apply { mkdirs() }

    fun append(level: String, message: String) {
        synchronized(lock) {
            runCatching {
                val date = LocalDate.now().format(dayFormatter)
                val file = File(logDir(), "$date.json")
                val array = if (file.exists()) JSONArray(file.readText()) else JSONArray()
                val obj = JSONObject()
                obj.put("timestamp", LocalDateTime.now().format(timestampFormatter))
                obj.put("level", normalizeLevel(level))
                obj.put("message", normalizeMessage(message))
                array.put(obj)
                file.writeText(array.toString())
            }.onFailure {
                Log.e(TAG, "append log failed", it)
            }
        }
    }

    fun exportLogsZip(): File? {
        synchronized(lock) {
            return runCatching {
                cleanupOldExportZips()
                val logs = logDir().listFiles { file ->
                    file.isFile && file.extension.lowercase(Locale.ROOT) == "json"
                }?.sortedBy { it.name }.orEmpty()
                if (logs.isEmpty()) return null

                val outputDir = exportDir()
                val output = File(outputDir, "GZHU_SeatBooking_logs_${LocalDateTime.now().format(exportNameFormatter)}.zip")
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    logs.forEach { logFile ->
                        zip.putNextEntry(ZipEntry(logFile.name))
                        logFile.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                append("INFO", "日志导出目录：${outputDir.absolutePath}")
                output
            }.onFailure {
                Log.e(TAG, "exportLogsZip failed", it)
            }.getOrNull()
        }
    }

    fun clearAllLogs() {
        synchronized(lock) {
            runCatching {
                logDir().listFiles()?.forEach { file ->
                    if (file.isFile && file.extension.lowercase(Locale.ROOT) == "json") {
                        file.delete()
                    }
                }
            }.onFailure {
                Log.e(TAG, "clearAllLogs failed", it)
            }
        }
    }

    fun todayLogs(): List<LogEntry> {
        synchronized(lock) {
            return runCatching {
                val date = LocalDate.now().format(dayFormatter)
                val file = File(logDir(), "$date.json")
                if (!file.exists()) return emptyList()
                val arr = JSONArray(file.readText())
                buildList {
                    for (index in 0 until arr.length()) {
                        val obj = arr.getJSONObject(index)
                        add(
                            LogEntry(
                                timestamp = obj.optString("timestamp"),
                                level = obj.optString("level"),
                                message = obj.optString("message")
                            )
                        )
                    }
                }
            }.getOrElse {
                Log.e(TAG, "todayLogs failed", it)
                emptyList()
            }
        }
    }

    fun cleanupOldLogs(keepDays: Long = 7) {
        synchronized(lock) {
            runCatching {
                val cutoff = LocalDate.now().minusDays(keepDays)
                logDir().listFiles()?.forEach { file ->
                    val date = runCatching { LocalDate.parse(file.nameWithoutExtension, dayFormatter) }.getOrNull()
                    if (date != null && date.isBefore(cutoff)) {
                        file.delete()
                    }
                }
                cleanupOldExportZips(keepDays)
            }.onFailure {
                Log.e(TAG, "cleanupOldLogs failed", it)
            }
        }
    }

    private fun cleanupOldExportZips(keepDays: Long = 7) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60L * 60L * 1000L
        exportDir().listFiles()?.forEach { file ->
            if (file.isFile && file.extension.lowercase(Locale.ROOT) == "zip" && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun resolveExportDir(): File {
        // Prefer public Download directory first for easier user access.
        val publicDownload = runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        if (publicDownload != null && (publicDownload.exists() || publicDownload.mkdirs())) {
            return File(publicDownload, "GZHU_SeatBooking").apply { mkdirs() }
        }

        // Fallback to app-specific external files directory when public Download is unavailable.
        val externalAppDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (externalAppDir != null && (externalAppDir.exists() || externalAppDir.mkdirs())) {
            return File(externalAppDir, "GZHU_SeatBooking").apply { mkdirs() }
        }

        // Last fallback keeps export functional even in constrained storage environments.
        return File(context.cacheDir, "log_exports")
    }

    private fun normalizeLevel(level: String): String {
        return when (level.trim().uppercase(Locale.ROOT)) {
            "ERROR" -> "ERROR"
            "SUCCESS" -> "SUCCESS"
            "WARN", "WARNING" -> "WARN"
            "DEBUG" -> "DEBUG"
            else -> "INFO"
        }
    }

    private fun normalizeMessage(message: String): String {
        return message
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}

