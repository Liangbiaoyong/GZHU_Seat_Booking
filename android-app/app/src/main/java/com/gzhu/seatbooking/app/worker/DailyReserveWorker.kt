package com.gzhu.seatbooking.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gzhu.seatbooking.app.GzhuSeatBookingApp

class DailyReserveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GzhuSeatBookingApp
        app.logRepository.append(
            "INFO",
            "DailyReserveWorker开始执行：attempt=$runAttemptCount id=$id tags=${tags.joinToString(",")}" 
        )
        val result = app.reservationEngine.runForTomorrow()
        app.logRepository.append(
            if (result.success) "SUCCESS" else "ERROR",
            "DailyReserveWorker执行完成：success=${result.success} message=${result.message}"
        )
        return if (result.success) Result.success() else Result.retry()
    }
}

