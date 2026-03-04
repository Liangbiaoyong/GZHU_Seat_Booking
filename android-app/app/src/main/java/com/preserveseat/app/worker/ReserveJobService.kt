package com.preserveseat.app.worker

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.preserveseat.app.PreserveSeatApp

class ReserveJobService : JobService() {
    private companion object {
        const val TAG = "ReserveJobService"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val extras = params?.extras
        val action = extras?.getString(AlarmDispatchWorker.KEY_ACTION).orEmpty().ifBlank { Scheduler.ACTION_DAILY }
        val captcha = extras?.getString(Scheduler.EXTRA_CAPTCHA).orEmpty()
        val token = extras?.getString(Scheduler.EXTRA_TEST_TOKEN).orEmpty()
        val triggerSource = extras?.getString(Scheduler.EXTRA_TRIGGER_SOURCE).orEmpty().ifBlank { "job" }
        val targetOffsetDays = extras?.getLong(Scheduler.EXTRA_TARGET_OFFSET_DAYS, 1L) ?: 1L
        val targetTriggerAt = extras?.getLong(Scheduler.EXTRA_TARGET_TRIGGER_AT, 0L) ?: 0L

        val data = Data.Builder()
            .putString(AlarmDispatchWorker.KEY_ACTION, action)
            .putString(Scheduler.EXTRA_CAPTCHA, captcha)
            .putString(Scheduler.EXTRA_TEST_TOKEN, token)
            .putString(Scheduler.EXTRA_TRIGGER_SOURCE, triggerSource)
            .putLong(Scheduler.EXTRA_TARGET_OFFSET_DAYS, targetOffsetDays)
            .putLong(Scheduler.EXTRA_TARGET_TRIGGER_AT, targetTriggerAt)
            .build()

        val request = OneTimeWorkRequestBuilder<AlarmDispatchWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
        appendLog(
            "INFO",
            "Job触发已转交WorkManager action=$action source=$triggerSource token=$token offset=$targetOffsetDays triggerAt=$targetTriggerAt"
        )
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private fun appendLog(level: String, message: String) {
        if (level == "ERROR") Log.e(TAG, message) else Log.i(TAG, message)
        runCatching {
            val app = applicationContext as PreserveSeatApp
            app.logRepository.append(level, message)
        }
    }
}
