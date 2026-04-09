package com.gzhu.seatbooking.app.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object SurvivalMonitor {
    const val ACTION_SURVIVAL = "com.gzhu.seatbooking.app.action.SURVIVAL_MONITOR"

    private const val SURVIVAL_WORK_NAME = "daily_survival_monitor_work"
    private const val REQ_SURVIVAL = 2001
    private const val PREF = "survival_monitor_pref"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NOTIFY_TIME = "notify_time"
    private const val KEY_NEXT_TRIGGER = "next_trigger"
    private const val KEY_CONSUMED_PREFIX = "consumed_"

    fun updateSchedule(context: Context, enabled: Boolean, timeStr: String) {
        val workManager = WorkManager.getInstance(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelSchedule(context, alarmManager, workManager)

        val notifyTime = normalizeTime(timeStr)
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_NOTIFY_TIME, notifyTime)
            .apply()

        if (!enabled) {
            appendLog(context, "INFO", "存活监测通知已关闭")
            return
        }

        val target = runCatching { LocalTime.parse(notifyTime) }.getOrDefault(LocalTime.MIDNIGHT)
        val now = LocalDateTime.now()
        var next = now.withHour(target.hour).withMinute(target.minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val delay = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val token = "s$triggerAt"

        val alarmReady = scheduleAlarm(context, alarmManager, token, triggerAt)
        val workReady = scheduleWork(workManager, token, triggerAt, delay)

        prefs.edit().putLong(KEY_NEXT_TRIGGER, triggerAt).apply()
        appendLog(
            context,
            "INFO",
            "存活监测通知已更新：enabled=$enabled time=$notifyTime next=$triggerAt alarm=$alarmReady work=$workReady"
        )
    }

    fun tryConsumeToken(context: Context, token: String): Boolean {
        val safeKey = token.ifBlank { "survival-${System.currentTimeMillis() / 60000}" }
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        synchronized(this) {
            val consumedKey = "$KEY_CONSUMED_PREFIX$safeKey"
            if (prefs.getBoolean(consumedKey, false)) {
                return false
            }
            prefs.edit()
                .putBoolean(consumedKey, true)
                .putLong("${consumedKey}_at", System.currentTimeMillis())
                .apply()
            return true
        }
    }

    private fun scheduleAlarm(context: Context, alarmManager: AlarmManager, token: String, triggerAt: Long): Boolean {
        val pending = pendingIntent(context, create = true, token = token, triggerAt = triggerAt, source = "alarm-survival")
            ?: return false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (throwable: Throwable) {
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
        }
        return pendingIntent(context, create = false) != null
    }

    private fun scheduleWork(workManager: WorkManager, token: String, triggerAt: Long, delay: Long): Boolean {
        val input = Data.Builder()
            .putString(AlarmDispatchWorker.KEY_ACTION, ACTION_SURVIVAL)
            .putString(Scheduler.EXTRA_TEST_TOKEN, token)
            .putString(Scheduler.EXTRA_TRIGGER_SOURCE, "work-survival")
            .putLong(Scheduler.EXTRA_TARGET_OFFSET_DAYS, 0L)
            .putLong(Scheduler.EXTRA_TARGET_TRIGGER_AT, triggerAt)
            .build()
        val request = OneTimeWorkRequestBuilder<AlarmDispatchWorker>()
            .setInputData(input)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            workManager.enqueueUniqueWork(SURVIVAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            true
        }.getOrElse { false }
    }

    private fun cancelSchedule(context: Context, alarmManager: AlarmManager, workManager: WorkManager) {
        workManager.cancelUniqueWork(SURVIVAL_WORK_NAME)
        pendingIntent(context, create = false)?.let { alarmManager.cancel(it) }
    }

    private fun pendingIntent(
        context: Context,
        create: Boolean,
        token: String = "",
        triggerAt: Long = 0L,
        source: String = "alarm-survival"
    ): PendingIntent? {
        val intent = Intent(context, ReserveAlarmReceiver::class.java).apply {
            action = ACTION_SURVIVAL
            if (token.isNotBlank()) putExtra(Scheduler.EXTRA_TEST_TOKEN, token)
            putExtra(Scheduler.EXTRA_TRIGGER_SOURCE, source)
            putExtra(Scheduler.EXTRA_TARGET_OFFSET_DAYS, 0L)
            putExtra(Scheduler.EXTRA_TARGET_TRIGGER_AT, triggerAt)
        }
        val flags = if (create) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQ_SURVIVAL, intent, flags)
    }

    private fun normalizeTime(value: String): String {
        val trimmed = value.trim()
        val parsed = runCatching { LocalTime.parse(trimmed) }.getOrNull() ?: LocalTime.MIDNIGHT
        return "%02d:%02d".format(parsed.hour, parsed.minute)
    }

    private fun appendLog(context: Context, level: String, message: String) {
        runCatching {
            val app = context.applicationContext as GzhuSeatBookingApp
            app.logRepository.append(level, message)
        }
    }
}
