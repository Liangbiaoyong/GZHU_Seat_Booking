package com.gzhu.seatbooking.app.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object Scheduler {
    const val ACTION_DAILY = "com.gzhu.seatbooking.app.action.DAILY_RESERVE"
    const val ACTION_DAILY_PRECHECK = "com.gzhu.seatbooking.app.action.DAILY_PRECHECK"

    const val EXTRA_CAPTCHA = "extra_captcha"
    const val EXTRA_TEST_TOKEN = "extra_test_token"
    const val EXTRA_TRIGGER_SOURCE = "extra_trigger_source"
    const val EXTRA_TARGET_OFFSET_DAYS = "extra_target_offset_days"
    const val EXTRA_TARGET_TRIGGER_AT = "extra_target_trigger_at"

    private const val PREF = "reserve_scheduler"
    private const val KEY_DAILY_ENABLED = "daily_enabled"
    private const val KEY_NEXT_TRIGGER = "next_trigger"
    private const val KEY_DAILY_PENDING_TOKEN = "daily_pending_token"
    private const val KEY_DAILY_ALARM_ACTIVE = "daily_alarm_active"
    private const val KEY_DAILY_LAST_CATCHUP = "daily_last_catchup"
    private const val KEY_CONSUMED_PREFIX = "consumed_"
    private const val TAG = "ReserveScheduler"

    private const val REQ_DAILY = 1001
    private const val REQ_DAILY_PRECHECK = 1003
    private const val DAILY_WORK_NAME = "daily_reserve_work"
    private const val DAILY_PRECHECK_WORK_NAME = "daily_precheck_work"
    private const val JOB_DAILY_ID = 30001
    private const val JOB_DAILY_PRECHECK_ID = 30003
    private const val PRECHECK_ADVANCE_MILLIS = 60_000L

    data class TriggerServiceState(
        val alarmEnabled: Boolean,
        val workEnabled: Boolean,
        val jobEnabled: Boolean,
        val nextTriggerMillis: Long
    )

    data class SchedulerStatus(
        val daily: TriggerServiceState
    )

    fun scheduleDaily(context: Context, triggerTime: LocalTime, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

        cancelDailyChannels(context)
        if (!enabled) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DAILY_ENABLED, false)
                .remove(KEY_NEXT_TRIGGER)
                .remove(KEY_DAILY_PENDING_TOKEN)
                .putBoolean(KEY_DAILY_ALARM_ACTIVE, false)
                .apply()
            appendLog(context, "INFO", "每日任务已关闭")
            return
        }

        val now = LocalDateTime.now()
        var next = now.withHour(triggerTime.hour).withMinute(triggerTime.minute).withSecond(0).withNano(0)
        if (next.isBefore(now)) next = next.plusDays(1)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val token = "d$triggerAt"
        val delay = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val precheckAt = (triggerAt - PRECHECK_ADVANCE_MILLIS).coerceAtLeast(System.currentTimeMillis() + 500L)
        val precheckDelay = (precheckAt - System.currentTimeMillis()).coerceAtLeast(0L)

        scheduleDailyAlarm(alarmManager, context, token, triggerAt)
        scheduleDailyWork(workManager, token, delay, triggerAt)
        scheduleDailyJob(jobScheduler, context, token, delay, triggerAt)
        scheduleDailyPrecheckAlarm(alarmManager, context, token, precheckAt, triggerAt)
        scheduleDailyPrecheckWork(workManager, token, precheckDelay, triggerAt)
        scheduleDailyPrecheckJob(jobScheduler, context, token, precheckDelay, triggerAt)

        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DAILY_ENABLED, true)
            .putLong(KEY_NEXT_TRIGGER, triggerAt)
            .putString(KEY_DAILY_PENDING_TOKEN, token)
            .putBoolean(KEY_DAILY_ALARM_ACTIVE, true)
            .apply()
        appendLog(
            context,
            "INFO",
            "每日任务已更新：next=${formatMillis(triggerAt)} precheck=${formatMillis(precheckAt)} token=$token（Alarm+Work+Job + 前1min预检）"
        )
    }

    fun isDailyTaskEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DAILY_ENABLED, false)
    }

    suspend fun queryServiceStatus(context: Context): SchedulerStatus = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val nextDaily = prefs.getLong(KEY_NEXT_TRIGGER, 0L)
        val alarmDailyEnabled = prefs.getBoolean(KEY_DAILY_ALARM_ACTIVE, false)

        val workManager = WorkManager.getInstance(context)
        val dailyWorkEnabled = workManager.getWorkInfosForUniqueWork(DAILY_WORK_NAME).get().any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
        }

        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val dailyJobEnabled = scheduler.allPendingJobs.any { it.id == JOB_DAILY_ID }

        return@withContext SchedulerStatus(
            daily = TriggerServiceState(alarmDailyEnabled, dailyWorkEnabled, dailyJobEnabled, nextDaily)
        )
    }

    fun tryConsumeExecutionToken(context: Context, action: String, token: String, source: String): Boolean {
        val key = if (token.isBlank()) "$action-${System.currentTimeMillis() / 60000}" else token
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        synchronized(this) {
            val consumedKey = "$KEY_CONSUMED_PREFIX$key"
            if (prefs.getBoolean(consumedKey, false)) {
                appendLog(context, "INFO", "触发已被消费，忽略重复执行 action=$action token=$key source=$source")
                return false
            }
            prefs.edit()
                .putBoolean(consumedKey, true)
                .putLong("${consumedKey}_at", System.currentTimeMillis())
                .remove(KEY_DAILY_PENDING_TOKEN)
                .apply()
            appendLog(context, "INFO", "触发已消费 action=$action token=$key source=$source")
            return true
        }
    }

    fun triggerDailyCatchUp(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val lastCatchUp = prefs.getLong(KEY_DAILY_LAST_CATCHUP, 0L)
        if (now - lastCatchUp < 2 * 60 * 1000L) return

        val token = "dc$now"
        val input = Data.Builder()
            .putString(AlarmDispatchWorker.KEY_ACTION, ACTION_DAILY)
            .putString(EXTRA_TEST_TOKEN, token)
            .putString(EXTRA_TRIGGER_SOURCE, "catchup-$reason")
            .putLong(EXTRA_TARGET_OFFSET_DAYS, 1L)
            .build()
        val req = OneTimeWorkRequestBuilder<AlarmDispatchWorker>()
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueue(req)

        prefs.edit()
            .putLong(KEY_DAILY_LAST_CATCHUP, now)
            .apply()
        appendLog(context, "INFO", "触发每日任务补偿执行 reason=$reason token=$token")
    }

    private fun scheduleDailyAlarm(alarmManager: AlarmManager, context: Context, token: String, triggerAt: Long) {
        val pi = pendingIntent(
            context,
            ACTION_DAILY,
            REQ_DAILY,
            create = true,
            token = token,
            triggerSource = "alarm-daily",
            targetOffsetDays = 1L,
            targetTriggerAt = triggerAt
        ) ?: return
        scheduleAlarmSafely(alarmManager, triggerAt, pi)
    }

    private fun scheduleDailyPrecheckAlarm(alarmManager: AlarmManager, context: Context, token: String, precheckAt: Long, triggerAt: Long) {
        val pi = pendingIntent(
            context,
            ACTION_DAILY_PRECHECK,
            REQ_DAILY_PRECHECK,
            create = true,
            token = token,
            triggerSource = "alarm-daily-precheck",
            targetOffsetDays = 1L,
            targetTriggerAt = triggerAt
        ) ?: return
        scheduleAlarmSafely(alarmManager, precheckAt, pi)
    }

    private fun scheduleDailyWork(workManager: WorkManager, token: String, delay: Long, triggerAt: Long) {
        val input = Data.Builder()
            .putString(AlarmDispatchWorker.KEY_ACTION, ACTION_DAILY)
            .putString(EXTRA_TEST_TOKEN, token)
            .putString(EXTRA_TRIGGER_SOURCE, "work-daily")
            .putLong(EXTRA_TARGET_OFFSET_DAYS, 1L)
            .putLong(EXTRA_TARGET_TRIGGER_AT, triggerAt)
            .build()
        val work = OneTimeWorkRequestBuilder<AlarmDispatchWorker>()
            .setInputData(input)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(DAILY_WORK_NAME, ExistingWorkPolicy.REPLACE, work)
    }

    private fun scheduleDailyPrecheckWork(workManager: WorkManager, token: String, delay: Long, triggerAt: Long) {
        val input = Data.Builder()
            .putString(AlarmDispatchWorker.KEY_ACTION, ACTION_DAILY_PRECHECK)
            .putString(EXTRA_TEST_TOKEN, token)
            .putString(EXTRA_TRIGGER_SOURCE, "work-daily-precheck")
            .putLong(EXTRA_TARGET_OFFSET_DAYS, 1L)
            .putLong(EXTRA_TARGET_TRIGGER_AT, triggerAt)
            .build()
        val work = OneTimeWorkRequestBuilder<AlarmDispatchWorker>()
            .setInputData(input)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(DAILY_PRECHECK_WORK_NAME, ExistingWorkPolicy.REPLACE, work)
    }

    private fun scheduleDailyJob(jobScheduler: JobScheduler, context: Context, token: String, delay: Long, triggerAt: Long) {
        val extras = PersistableBundle().apply {
            putString(AlarmDispatchWorker.KEY_ACTION, ACTION_DAILY)
            putString(EXTRA_TEST_TOKEN, token)
            putString(EXTRA_TRIGGER_SOURCE, "job-daily")
            putLong(EXTRA_TARGET_OFFSET_DAYS, 1L)
            putLong(EXTRA_TARGET_TRIGGER_AT, triggerAt)
        }
        val info = JobInfo.Builder(JOB_DAILY_ID, ComponentName(context, ReserveJobService::class.java))
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + 60_000L)
            .setPersisted(true)
            .setExtras(extras)
            .build()
        jobScheduler.cancel(JOB_DAILY_ID)
        jobScheduler.schedule(info)
    }

    private fun scheduleDailyPrecheckJob(jobScheduler: JobScheduler, context: Context, token: String, delay: Long, triggerAt: Long) {
        val extras = PersistableBundle().apply {
            putString(AlarmDispatchWorker.KEY_ACTION, ACTION_DAILY_PRECHECK)
            putString(EXTRA_TEST_TOKEN, token)
            putString(EXTRA_TRIGGER_SOURCE, "job-daily-precheck")
            putLong(EXTRA_TARGET_OFFSET_DAYS, 1L)
            putLong(EXTRA_TARGET_TRIGGER_AT, triggerAt)
        }
        val info = JobInfo.Builder(JOB_DAILY_PRECHECK_ID, ComponentName(context, ReserveJobService::class.java))
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + 60_000L)
            .setPersisted(true)
            .setExtras(extras)
            .build()
        jobScheduler.cancel(JOB_DAILY_PRECHECK_ID)
        jobScheduler.schedule(info)
    }

    private fun cancelDailyChannels(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(DAILY_WORK_NAME)
        workManager.cancelUniqueWork(DAILY_PRECHECK_WORK_NAME)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pendingIntent(context, ACTION_DAILY, REQ_DAILY, create = false)?.let { alarmManager.cancel(it) }
        pendingIntent(context, ACTION_DAILY_PRECHECK, REQ_DAILY_PRECHECK, create = false)?.let { alarmManager.cancel(it) }

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(JOB_DAILY_ID)
        jobScheduler.cancel(JOB_DAILY_PRECHECK_ID)

        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DAILY_ALARM_ACTIVE, false)
            .apply()
    }

    private fun pendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        create: Boolean,
        captcha: String = "",
        token: String = "",
        triggerSource: String = "",
        targetOffsetDays: Long = 1L,
        targetTriggerAt: Long = 0L
    ): PendingIntent? {
        val intent = Intent(context, ReserveAlarmReceiver::class.java).apply {
            this.action = action
            if (captcha.isNotBlank()) putExtra(EXTRA_CAPTCHA, captcha)
            if (token.isNotBlank()) putExtra(EXTRA_TEST_TOKEN, token)
            if (triggerSource.isNotBlank()) putExtra(EXTRA_TRIGGER_SOURCE, triggerSource)
            putExtra(EXTRA_TARGET_OFFSET_DAYS, targetOffsetDays)
            putExtra(EXTRA_TARGET_TRIGGER_AT, targetTriggerAt)
        }
        val flags = if (create) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun scheduleAlarmSafely(alarmManager: AlarmManager, triggerAt: Long, pending: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                return
            }
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } catch (throwable: Throwable) {
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
        }
    }

    private fun appendLog(context: Context, level: String, message: String) {
        if (level == "ERROR") Log.e(TAG, message) else Log.i(TAG, message)
        runCatching {
            val app = context.applicationContext as GzhuSeatBookingApp
            app.logRepository.append(level, message)
        }
    }

    private fun formatMillis(millis: Long): String {
        val dt = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}

