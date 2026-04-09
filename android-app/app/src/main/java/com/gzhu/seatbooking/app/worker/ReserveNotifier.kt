package com.gzhu.seatbooking.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import com.gzhu.seatbooking.app.data.model.ReservationResult
import com.gzhu.seatbooking.app.domain.ReservationResultPipeline
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ReserveNotifier {
    private const val CHANNEL_ID = "reserve_result_channel"
    private const val CHANNEL_NAME = "预约结果通知"
    private const val SURVIVAL_CHANNEL_ID = "survival_monitor_channel"
    private const val SURVIVAL_CHANNEL_NAME = "存活监测通知"

    fun notifySurvivalAction(context: Context, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                appendLog(context, "ERROR", "通知发送失败：缺少POST_NOTIFICATIONS权限")
                return
            }
        }
        
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(SURVIVAL_CHANNEL_ID, SURVIVAL_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(context, SURVIVAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            
        manager.notify("survival".hashCode(), notification)
        appendLog(context, "INFO", "存活监测通知已发送：$message")
    }

    fun notifyReservationResult(context: Context, triggerSource: String, titlePrefix: String, results: List<ReservationResult>) {
        val reportResults = ReservationResultPipeline.normalizeForReporting(results)
        val success = reportResults.filter { it.success }
        val fail = reportResults.size - success.size

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                appendLog(context, "ERROR", "通知发送失败：缺少POST_NOTIFICATIONS权限，source=$triggerSource")
                return
            }
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val detail = if (reportResults.isEmpty()) {
            "本次无可执行预约任务（通常是明日未启用任何时段）"
        } else {
            reportResults.take(4).joinToString("；") {
                val date = it.date.ifBlank { "-" }
                val seat = it.seatCode.ifBlank { "-" }
                val range = "${it.start}-${it.end}".trim('-')
                val state = if (it.success) "成功" else "失败"
                "$date $seat $range $state"
            }
        }
        val runTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val title = if (reportResults.isEmpty()) {
            "$runTime $titlePrefix 无任务"
        } else {
            "$runTime $titlePrefix 成功${success.size}失败$fail"
        }
        val sourceText = readableSource(triggerSource)
        val content = "执行明细：$detail；唤醒来源：$sourceText"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        appendLog(
            context,
            "INFO",
            "通知已发送：source=$triggerSource title=$titlePrefix rawTotal=${results.size} reportTotal=${reportResults.size} success=${success.size} fail=$fail"
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }
    }

    private fun appendLog(context: Context, level: String, message: String) {
        runCatching {
            val app = context.applicationContext as GzhuSeatBookingApp
            app.logRepository.append(level, message)
        }
    }

    private fun readableSource(source: String): String {
        val raw = source.lowercase()
        return when {
            raw.contains("alarm") -> "AlarmManager"
            raw.contains("work") -> "WorkManager"
            raw.contains("job") -> "JobScheduler"
            else -> source
        }
    }
}

