package com.preserveseat.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.preserveseat.app.PreserveSeatApp
import com.preserveseat.app.data.model.ReservationResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ReserveNotifier {
    private const val CHANNEL_ID = "reserve_result_channel"
    private const val CHANNEL_NAME = "预约结果通知"

    fun notifyReservationResult(context: Context, triggerSource: String, titlePrefix: String, results: List<ReservationResult>) {
        val success = results.filter { it.success }
        val fail = results.size - success.size
        if (results.isEmpty()) return

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

        val detail = results.take(4).joinToString("；") {
            val date = it.date.ifBlank { "-" }
            val seat = it.seatCode.ifBlank { "-" }
            val range = "${it.start}-${it.end}".trim('-')
            val state = if (it.success) "成功" else "失败"
            "$date $seat $range $state"
        }
        val runTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val title = "$runTime $titlePrefix 成功${success.size}失败$fail"
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
        appendLog(context, "INFO", "通知已发送：source=$triggerSource title=$titlePrefix success=${success.size} fail=$fail")
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }
    }

    private fun appendLog(context: Context, level: String, message: String) {
        runCatching {
            val app = context.applicationContext as PreserveSeatApp
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
