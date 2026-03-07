package com.gzhu.seatbooking.app.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

data class TimeRangeConfig(
    val id: String = UUID.randomUUID().toString(),
    val start: String = "09:00",
    val end: String = "13:00",
    val enabled: Boolean = false
)

fun buildDefaultSlotByIndex(index: Int): TimeRangeConfig {
    return when (index) {
        0 -> TimeRangeConfig(start = "09:00", end = "13:00", enabled = false)
        1 -> TimeRangeConfig(start = "13:00", end = "16:00", enabled = false)
        2 -> TimeRangeConfig(start = "16:00", end = "20:00", enabled = false)
        3 -> TimeRangeConfig(start = "20:00", end = "22:15", enabled = false)
        else -> TimeRangeConfig(start = "", end = "", enabled = false)
    }
}

data class AppConfig(
    val account: String = "",
    val password: String = "",
    val autoEnabled: Boolean = false,
    val triggerTime: String = "07:15",
    val roomId: Int = 0,
    val roomName: String = "",
    val seatCode: String = "",
    val seatDevId: Int = 0,
    val weekSchedule: Map<DayOfWeek, List<TimeRangeConfig>> = DayOfWeek.entries.associateWith { listOf(buildDefaultSlotByIndex(0)) },
    val token: String = "",
    val cookieHeader: String = "",
    val lastRunAt: String = ""
)

data class ReservationRequest(
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val seatCode: String,
    val seatDevId: Int,
    val captcha: String = ""
)

data class ReservationResult(
    val success: Boolean,
    val message: String,
    val requestAt: LocalDateTime,
    val date: String = "",
    val code: Int = -1,
    val raw: String = "",
    val seatCode: String = "",
    val start: String = "",
    val end: String = ""
)

data class OccupyBlock(
    val start: LocalTime,
    val end: LocalTime
)

data class RoomOption(
    val roomId: Int,
    val roomName: String,
    val pathName: String
)

data class SeatOption(
    val devId: Int,
    val seatCode: String,
    val roomId: Int,
    val roomName: String,
    val openStart: String = "08:30",
    val openEnd: String = "22:15",
    val intervalMinutes: Int = 5,
    val minResvMinutes: Int = 60,
    val maxResvMinutes: Int = 240,
    val blocks: List<OccupyBlock> = emptyList()
)

data class LogEntry(
    val timestamp: String,
    val level: String,
    val message: String
)

