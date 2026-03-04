package com.gzhu.seatbooking.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gzhu.seatbooking.app.GzhuSeatBookingApp

class ReserveAlarmReceiver : BroadcastReceiver() {
    private companion object {
        const val TAG = "ReserveAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val testToken = intent.getStringExtra(Scheduler.EXTRA_TEST_TOKEN).orEmpty()
        val captcha = intent.getStringExtra(Scheduler.EXTRA_CAPTCHA).orEmpty()
        val targetOffsetDays = intent.getLongExtra(Scheduler.EXTRA_TARGET_OFFSET_DAYS, 1L)
        val targetTriggerAt = intent.getLongExtra(Scheduler.EXTRA_TARGET_TRIGGER_AT, 0L)
        val triggerSource = intent.getStringExtra(Scheduler.EXTRA_TRIGGER_SOURCE).orEmpty().ifBlank { "alarm-unknown" }
        val data = Data.Builder()
            .putString(AlarmDispatchWorker.KEY_ACTION, action)
            .putString(Scheduler.EXTRA_CAPTCHA, captcha)
            .putString(Scheduler.EXTRA_TEST_TOKEN, testToken)
            .putString(Scheduler.EXTRA_TRIGGER_SOURCE, triggerSource)
            .putLong(Scheduler.EXTRA_TARGET_OFFSET_DAYS, targetOffsetDays)
            .putLong(Scheduler.EXTRA_TARGET_TRIGGER_AT, targetTriggerAt)
            .build()

        val req = OneTimeWorkRequestBuilder<AlarmDispatchWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(req)
        appendLog(
            context,
            "INFO",
            "Alarm触发已转交WorkManager action=$action source=$triggerSource token=$testToken offset=$targetOffsetDays triggerAt=$targetTriggerAt"
        )
        Log.d("ReserveAlarmReceiver", "enqueue dispatch action=$action source=$triggerSource token=$testToken triggerAt=$targetTriggerAt")
    }

    private fun appendLog(context: Context, level: String, message: String) {
        if (level == "ERROR") Log.e(TAG, message) else Log.i(TAG, message)
        runCatching {
            val app = context.applicationContext as GzhuSeatBookingApp
            app.logRepository.append(level, message)
        }
    }
}

