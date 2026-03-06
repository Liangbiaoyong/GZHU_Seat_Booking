package com.gzhu.seatbooking.app

import android.app.Application
import com.gzhu.seatbooking.app.data.local.ConfigStore
import com.gzhu.seatbooking.app.data.local.LogRepository
import com.gzhu.seatbooking.app.data.local.ReservationResultRepository
import com.gzhu.seatbooking.app.data.network.LibraryApi
import com.gzhu.seatbooking.app.domain.ReservationEngine
import com.gzhu.seatbooking.app.worker.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime

class GzhuSeatBookingApp : Application() {
    lateinit var configStore: ConfigStore
    lateinit var logRepository: LogRepository
    lateinit var reservationResultRepository: ReservationResultRepository
    lateinit var reservationEngine: ReservationEngine

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        logRepository = LogRepository(this)
        reservationResultRepository = ReservationResultRepository(this)
        reservationEngine = ReservationEngine(
            configStore,
            logRepository,
            LibraryApi(this) { level, message -> logRepository.append(level, message) }
        )

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val cfg = configStore.getConfig()
                val time = runCatching { LocalTime.parse(cfg.triggerTime) }.getOrDefault(LocalTime.of(7, 15))
                Scheduler.scheduleDaily(this@GzhuSeatBookingApp, time, cfg.autoEnabled)
                logRepository.append("INFO", "应用启动自检：已重建每日调度 auto=${cfg.autoEnabled} trigger=${cfg.triggerTime}")
            }.onFailure {
                logRepository.append("ERROR", "应用启动自检失败：${it.message.orEmpty()}")
            }
        }
    }
}

