package com.gzhu.seatbooking.app.data.local

import android.content.Context
import android.util.Log
import com.gzhu.seatbooking.app.data.model.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogRepository(private val context: Context) {
    private companion object {
        private const val TAG = "LogRepository"
    }

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val lock = Any()

    private fun logDir(): File = File(context.filesDir, "logs").apply { mkdirs() }

    fun append(level: String, message: String) {
        synchronized(lock) {
            runCatching {
                val date = LocalDate.now().format(dayFormatter)
                val file = File(logDir(), "$date.json")
                val array = if (file.exists()) JSONArray(file.readText()) else JSONArray()
                val obj = JSONObject()
                obj.put("timestamp", LocalDateTime.now().format(timestampFormatter))
                obj.put("level", level)
                obj.put("message", message)
                array.put(obj)
                file.writeText(array.toString())
            }.onFailure {
                Log.e(TAG, "append log failed", it)
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
            }.onFailure {
                Log.e(TAG, "cleanupOldLogs failed", it)
            }
        }
    }
}

