package com.gzhu.seatbooking.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        val recoverableActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (action !in recoverableActions) return
        val app = context.applicationContext as GzhuSeatBookingApp
        CoroutineScope(Dispatchers.IO).launch {
            val cfg = app.configStore.getConfig()
            val time = runCatching { LocalTime.parse(cfg.triggerTime) }.getOrDefault(LocalTime.of(7, 15))
            app.logRepository.append("INFO", "系统广播触发调度恢复：action=$action auto=${cfg.autoEnabled} trigger=${cfg.triggerTime}")
            Scheduler.scheduleDaily(context, time, cfg.autoEnabled)
            app.logRepository.append("INFO", "系统广播调度恢复完成：action=$action")
        }
    }
}

