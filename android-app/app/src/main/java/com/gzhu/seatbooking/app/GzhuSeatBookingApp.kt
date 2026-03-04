package com.gzhu.seatbooking.app

import android.app.Application
import com.gzhu.seatbooking.app.data.local.ConfigStore
import com.gzhu.seatbooking.app.data.local.LogRepository
import com.gzhu.seatbooking.app.data.local.ReservationResultRepository
import com.gzhu.seatbooking.app.data.network.LibraryApi
import com.gzhu.seatbooking.app.domain.ReservationEngine

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
    }
}

