package com.gzhu.seatbooking.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import com.gzhu.seatbooking.app.domain.ReservationResultPipeline

class AlarmDispatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GzhuSeatBookingApp
        val action = inputData.getString(KEY_ACTION).orEmpty()
        val captcha = inputData.getString(Scheduler.EXTRA_CAPTCHA).orEmpty()
        val token = inputData.getString(Scheduler.EXTRA_TEST_TOKEN).orEmpty()
        val triggerSource = inputData.getString(Scheduler.EXTRA_TRIGGER_SOURCE).orEmpty().ifBlank { "dispatch-worker" }
        val targetOffsetDays = inputData.getLong(Scheduler.EXTRA_TARGET_OFFSET_DAYS, 1L)
        val scheduledTriggerAtMillis = inputData.getLong(Scheduler.EXTRA_TARGET_TRIGGER_AT, 0L)

        app.logRepository.append(
            "INFO",
            "Worker兜底触发: action=$action source=$triggerSource token=$token triggerAt=$scheduledTriggerAtMillis"
        )
        return try {
            val outcome = ReserveTaskRunner.run(
                context = applicationContext,
                action = action,
                captcha = captcha,
                token = token,
                triggerSource = triggerSource,
                targetOffsetDays = targetOffsetDays,
                scheduledTriggerAtMillis = scheduledTriggerAtMillis
            )
            if (outcome != null) {
                val summary = ReservationResultPipeline.record(app, triggerSource, outcome.title, outcome.results)
                ReserveNotifier.notifyReservationResult(applicationContext, triggerSource, outcome.title, outcome.results)
                app.logRepository.append("INFO", "Worker执行结束：${outcome.title} 成功${summary.successCount}，失败${summary.failCount}")
            }
            Result.success()
        } catch (throwable: Throwable) {
            app.logRepository.append(
                "ERROR",
                "Worker兜底执行异常: action=$action source=$triggerSource token=$token message=${throwable.message.orEmpty()}"
            )
            Result.retry()
        }
    }

    companion object {
        const val KEY_ACTION = "action"
    }
}

