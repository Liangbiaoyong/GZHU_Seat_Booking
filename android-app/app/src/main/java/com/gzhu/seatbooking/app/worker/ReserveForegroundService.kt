package com.gzhu.seatbooking.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import com.gzhu.seatbooking.app.domain.ReservationResultPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ReserveForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private companion object {
        const val TAG = "ReserveFgService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        val captcha = intent?.getStringExtra(Scheduler.EXTRA_CAPTCHA).orEmpty()
        val testToken = intent?.getStringExtra(Scheduler.EXTRA_TEST_TOKEN).orEmpty()
        val targetOffsetDays = intent?.getLongExtra(Scheduler.EXTRA_TARGET_OFFSET_DAYS, 1L) ?: 1L
        val scheduledTriggerAtMillis = intent?.getLongExtra(Scheduler.EXTRA_TARGET_TRIGGER_AT, 0L) ?: 0L
        val triggerSource = intent?.getStringExtra(Scheduler.EXTRA_TRIGGER_SOURCE).orEmpty().ifBlank { "service-direct" }
        appendLog(
            "INFO",
            "前台服务启动: action=$action source=$triggerSource startId=$startId triggerAt=$scheduledTriggerAtMillis sdk=${Build.VERSION.SDK_INT}"
        )

        try {
            startForeground(2001, buildNotification("正在执行预约任务"))
        } catch (securityException: SecurityException) {
            val hasDataSyncPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                checkSelfPermission("android.permission.FOREGROUND_SERVICE_DATA_SYNC") == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            appendLog(
                "ERROR",
                "startForeground失败: ${securityException.message.orEmpty()} hasDataSyncPermission=$hasDataSyncPermission"
            )
            Log.e(TAG, "startForeground failed", securityException)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (throwable: Throwable) {
            appendLog("ERROR", "startForeground异常: ${throwable.message.orEmpty()}")
            Log.e(TAG, "startForeground throwable", throwable)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                val app = application as GzhuSeatBookingApp
                val outcome = ReserveTaskRunner.run(
                    context = this@ReserveForegroundService,
                    action = action,
                    captcha = captcha,
                    token = testToken,
                    triggerSource = triggerSource,
                    targetOffsetDays = targetOffsetDays,
                    scheduledTriggerAtMillis = scheduledTriggerAtMillis
                )
                if (outcome == null) {
                    appendLog("INFO", "统一执行器未返回结果，可能已去重 action=$action source=$triggerSource")
                    return@launch
                }
                val appForResult = application as GzhuSeatBookingApp
                val summary = ReservationResultPipeline.record(appForResult, triggerSource, outcome.title, outcome.results)
                appendLog("INFO", "前台服务执行结束：${outcome.title} 成功${summary.successCount}，失败${summary.failCount}")
                ReserveNotifier.notifyReservationResult(this@ReserveForegroundService, triggerSource, outcome.title, outcome.results)
            } catch (throwable: Throwable) {
                appendLog(
                    "ERROR",
                    "前台服务任务执行异常: action=$action source=$triggerSource token=$testToken message=${throwable.message.orEmpty()}"
                )
                Log.e(TAG, "service execution failed", throwable)
            } finally {
                appendLog("INFO", "前台服务结束: action=$action source=$triggerSource startId=$startId")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val channelId = "reserve_service_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "预约服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("广州大学图书馆座位预定")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setOngoing(true)
            .build()
    }

    private fun appendLog(level: String, message: String) {
        runCatching {
            val app = application as GzhuSeatBookingApp
            app.logRepository.append(level, message)
        }
        if (level == "ERROR") {
            Log.e(TAG, message)
        } else {
            Log.i(TAG, message)
        }
    }
}

