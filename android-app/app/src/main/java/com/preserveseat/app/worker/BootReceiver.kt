package com.preserveseat.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.preserveseat.app.PreserveSeatApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as PreserveSeatApp
        CoroutineScope(Dispatchers.IO).launch {
            val cfg = app.configStore.getConfig()
            val time = runCatching { LocalTime.parse(cfg.triggerTime) }.getOrDefault(LocalTime.of(7, 16))
            Scheduler.scheduleDaily(context, time, cfg.autoEnabled)
        }
    }
}
