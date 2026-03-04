package com.gzhu.seatbooking.app.domain

import java.time.LocalTime

object ScheduleValidator {
    private val minStart = LocalTime.of(8, 30)
    private val maxEnd = LocalTime.of(22, 30)

    fun validate(start: String, end: String): String? {
        val startTime = runCatching { LocalTime.parse(start) }.getOrNull() ?: return "开始时间格式错误"
        val endTime = runCatching { LocalTime.parse(end) }.getOrNull() ?: return "结束时间格式错误"
        if (!isFiveMinuteStep(startTime) || !isFiveMinuteStep(endTime)) return "时间必须按5分钟粒度"
        if (startTime < minStart) return "最早开始时间是08:30"
        if (endTime > maxEnd) return "最晚结束时间是22:30"
        val minutes = java.time.Duration.between(startTime, endTime).toMinutes()
        if (minutes < 60) return "单次预约最低1小时"
        if (minutes > 240) return "单次预约最高4小时"
        if (!endTime.isAfter(startTime)) return "结束时间必须晚于开始时间"
        return null
    }

    private fun isFiveMinuteStep(time: LocalTime): Boolean = time.minute % 5 == 0
}

