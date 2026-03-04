package com.preserveseat.app

import android.app.Application
import com.preserveseat.app.data.local.ConfigStore
import com.preserveseat.app.data.local.LogRepository
import com.preserveseat.app.data.local.ReservationResultRepository
import com.preserveseat.app.data.network.LibraryApi
import com.preserveseat.app.domain.ReservationEngine

class PreserveSeatApp : Application() {
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
