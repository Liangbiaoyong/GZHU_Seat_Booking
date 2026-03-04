package com.gzhu.seatbooking.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gzhu.seatbooking.app.GzhuSeatBookingApp

class DailyReserveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GzhuSeatBookingApp
        app.logRepository.append("INFO", "定时任务开始执行")
        val result = app.reservationEngine.runForTomorrow()
        app.logRepository.append(if (result.success) "SUCCESS" else "ERROR", "定时任务执行完成: ${result.message}")
        return if (result.success) Result.success() else Result.retry()
    }
}

