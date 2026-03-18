package com.gzhu.seatbooking.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import com.gzhu.seatbooking.app.domain.ReservationResultPipeline

class DailyReserveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GzhuSeatBookingApp
        app.logRepository.append(
            "INFO",
            "DailyReserveWorker开始执行：attempt=$runAttemptCount id=$id tags=${tags.joinToString(",")}" 
        )
        val results = app.reservationEngine.runForTomorrowBatch()
        val summary = ReservationResultPipeline.record(app, "daily-worker-legacy", "每日预约", results)
        app.logRepository.append(
            if (summary.failCount == 0) "SUCCESS" else "ERROR",
            "DailyReserveWorker执行完成：success=${summary.successCount} fail=${summary.failCount}"
        )
        return if (summary.failCount == 0) Result.success() else Result.retry()
    }
}

