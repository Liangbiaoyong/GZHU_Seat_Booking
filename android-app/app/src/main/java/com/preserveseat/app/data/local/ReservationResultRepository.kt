package com.preserveseat.app.data.local

import android.content.Context
import android.util.Log
import com.preserveseat.app.data.model.ReservationResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ReservationResultRepository(private val context: Context) {
    private companion object {
        private const val TAG = "ReservationResultRepo"
        private const val MAX_KEEP = 500
    }

    private val lock = Any()
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun resultDir(): File = File(context.filesDir, "reservation_results").apply { mkdirs() }

    private fun todayFile(): File {
        val today = LocalDate.now().format(dayFormatter)
        return File(resultDir(), "$today.json")
    }

    fun append(results: List<ReservationResult>) {
        if (results.isEmpty()) return
        synchronized(lock) {
            try {
                cleanupOldFiles(keepDays = 2)
                val file = todayFile()
                val array = if (file.exists()) JSONArray(file.readText()) else JSONArray()
                results.forEach { result ->
                    array.put(JSONObject().apply {
                        put("success", result.success)
                        put("message", result.message)
                        put("requestAt", result.requestAt.toString())
                        put("date", result.date)
                        put("code", result.code)
                        put("raw", result.raw)
                        put("seatCode", result.seatCode)
                        put("start", result.start)
                        put("end", result.end)
                    })
                }
                val trimmed = trimTail(array, MAX_KEEP)
                file.writeText(trimmed.toString())
            } catch (throwable: Throwable) {
                Log.e(TAG, "append failed", throwable)
            }
        }
    }

    fun listAll(): List<ReservationResult> {
        synchronized(lock) {
            return try {
                cleanupOldFiles(keepDays = 2)
                val file = todayFile()
                if (!file.exists()) return emptyList()
                val arr = JSONArray(file.readText())
                buildList {
                    for (index in 0 until arr.length()) {
                        val obj = arr.optJSONObject(index) ?: continue
                        val requestAt = runCatching {
                            LocalDateTime.parse(obj.optString("requestAt"))
                        }.getOrDefault(LocalDateTime.now())
                        add(
                            ReservationResult(
                                success = obj.optBoolean("success", false),
                                message = obj.optString("message"),
                                requestAt = requestAt,
                                date = obj.optString("date"),
                                code = obj.optInt("code", -1),
                                raw = obj.optString("raw"),
                                seatCode = obj.optString("seatCode"),
                                start = obj.optString("start"),
                                end = obj.optString("end")
                            )
                        )
                    }
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "listAll failed", throwable)
                emptyList()
            }
        }
    }

    private fun cleanupOldFiles(keepDays: Long) {
        val cutoff = LocalDate.now().minusDays(keepDays)
        resultDir().listFiles()?.forEach { file ->
            val day = runCatching { LocalDate.parse(file.nameWithoutExtension, dayFormatter) }.getOrNull() ?: return@forEach
            if (day.isBefore(cutoff)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun trimTail(source: JSONArray, keep: Int): JSONArray {
        if (source.length() <= keep) return source
        val from = source.length() - keep
        val out = JSONArray()
        for (i in from until source.length()) {
            out.put(source.opt(i))
        }
        return out
    }
}
