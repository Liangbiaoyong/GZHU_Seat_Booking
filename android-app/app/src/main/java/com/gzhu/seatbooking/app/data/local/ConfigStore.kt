package com.gzhu.seatbooking.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gzhu.seatbooking.app.data.model.AppConfig
import com.gzhu.seatbooking.app.data.model.TimeRangeConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.DayOfWeek

private val Context.dataStore by preferencesDataStore(name = "preserve_seat_settings")

class ConfigStore(private val context: Context) {
    private val configKey = stringPreferencesKey("app_config_json")

    val configFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        val json = prefs[configKey] ?: return@map AppConfig()
        runCatching { fromJson(json) }.getOrDefault(AppConfig())
    }

    suspend fun getConfig(): AppConfig {
        return configFlow.first()
    }

    suspend fun save(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[configKey] = toJson(config)
        }
    }

    private fun toJson(config: AppConfig): String {
        val root = JSONObject()
        root.put("account", config.account)
        root.put("password", config.password)
        root.put("activated", config.activated)
        root.put("autoEnabled", config.autoEnabled)
        root.put("triggerTime", config.triggerTime)
        root.put("roomId", config.roomId)
        root.put("roomName", config.roomName)
        root.put("seatCode", config.seatCode)
        root.put("seatDevId", config.seatDevId)
        root.put("token", config.token)
        root.put("cookieHeader", config.cookieHeader)
        root.put("lastRunAt", config.lastRunAt)
        val week = JSONObject()
        config.weekSchedule.forEach { (day, items) ->
            val arr = org.json.JSONArray()
            items.forEach { item ->
                arr.put(JSONObject().apply {
                    put("id", item.id)
                    put("start", item.start)
                    put("end", item.end)
                    put("enabled", item.enabled)
                })
            }
            week.put(day.name, arr)
        }
        root.put("week", week)
        return root.toString()
    }

    private fun fromJson(text: String): AppConfig {
        val root = JSONObject(text)
        val weekJson = root.optJSONObject("week") ?: JSONObject()
        val week = DayOfWeek.entries.associateWith { day ->
            val raw = weekJson.opt(day.name)
            when {
                raw is org.json.JSONArray -> {
                    buildList {
                        for (index in 0 until raw.length()) {
                            val obj = raw.optJSONObject(index) ?: continue
                            add(
                                TimeRangeConfig(
                                    id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                                    start = obj.optString("start", "20:00"),
                                    end = obj.optString("end", "22:15"),
                                    enabled = obj.optBoolean("enabled", true)
                                )
                            )
                        }
                    }.ifEmpty { listOf(TimeRangeConfig()) }
                }
                raw is JSONObject -> {
                    listOf(
                        TimeRangeConfig(
                            start = raw.optString("start", "20:00"),
                            end = raw.optString("end", "22:15"),
                            enabled = raw.optBoolean("enabled", true)
                        )
                    )
                }
                else -> listOf(TimeRangeConfig())
            }
        }
        return AppConfig(
            account = root.optString("account", ""),
            password = root.optString("password", ""),
            activated = root.optBoolean("activated", false),
            autoEnabled = root.optBoolean("autoEnabled", false),
            triggerTime = root.optString("triggerTime", "07:15"),
            roomId = root.optInt("roomId", 0),
            roomName = root.optString("roomName", ""),
            seatCode = root.optString("seatCode", ""),
            seatDevId = root.optInt("seatDevId", 0),
            weekSchedule = week,
            token = root.optString("token", ""),
            cookieHeader = root.optString("cookieHeader", ""),
            lastRunAt = root.optString("lastRunAt", "")
        )
    }
}

