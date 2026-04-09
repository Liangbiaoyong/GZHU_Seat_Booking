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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object SurvivalMonitor {
    const val ACTION_SURVIVAL = "com.gzhu.seatbooking.app.action.SURVIVAL_MONITOR"
    const val SURVIVAL_WORK_NAME = "gzhu_survival_monitor_work"
    const val REQ_SURVIVAL = 2001

    fun updateSchedule(context: Context, enabled: Boolean, timeStr: String) {
        val workManager = WorkManager.getInstance(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ReserveAlarmReceiver::class.java).apply {
            action = ACTION_SURVIVAL
        }
        
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getBroadcast(context, REQ_SURVIVAL, intent, flag)

        if (!enabled) {
            alarmManager.cancel(pending)
            workManager.cancelUniqueWork(SURVIVAL_WORK_NAME)
            return
        }

        val targetTime = try {
            LocalTime.parse(timeStr)
        } catch (e: Exception) {
            LocalTime.MIDNIGHT
        }
        
        var nextTrigger = LocalDateTime.now().withHour(targetTime.hour).withMinute(targetTime.minute).withSecond(0).withNano(0)
        if (nextTrigger.isBefore(LocalDateTime.now())) {
            nextTrigger = nextTrigger.plusDays(1)
        }
        val triggerAtMillis = nextTrigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val delayMillis = triggerAtMillis - System.currentTimeMillis()

        // 1. AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (e: Exception) {
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending) }
        }

        // 2. WorkManager
        val input = Data.Builder()
            .putString(AlarmDispatchWorker.KEY_ACTION, ACTION_SURVIVAL)
            .putString(Scheduler.EXTRA_TRIGGER_SOURCE, "work-survival")
            .build()
        val work = OneTimeWorkRequestBuilder<AlarmDispatchWorker>()
            .setInputData(input)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(SURVIVAL_WORK_NAME, ExistingWorkPolicy.REPLACE, work)
    }
}
