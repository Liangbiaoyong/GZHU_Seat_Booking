package com.preserveseat.app.worker

import android.content.Context
import com.preserveseat.app.PreserveSeatApp
import com.preserveseat.app.data.model.ReservationResult
import java.time.LocalTime

object ReserveTaskRunner {
    data class RunOutcome(
        val title: String,
        val results: List<ReservationResult>
    )

    suspend fun run(
        context: Context,
        action: String,
        captcha: String,
        token: String,
        triggerSource: String,
        targetOffsetDays: Long,
        scheduledTriggerAtMillis: Long = 0L
    ): RunOutcome? {
        val app = context.applicationContext as PreserveSeatApp
        return when (action) {
            Scheduler.ACTION_DAILY -> executeReserveAction(
                context = context,
                action = action,
                captcha = captcha,
                token = token,
                triggerSource = triggerSource,
                targetOffsetDays = targetOffsetDays
            )

            Scheduler.ACTION_DAILY_PRECHECK -> {
                runPrecheck(
                    context = context,
                    action = action,
                    captcha = captcha,
                    token = token,
                    triggerSource = triggerSource,
                    targetOffsetDays = targetOffsetDays,
                    scheduledTriggerAtMillis = scheduledTriggerAtMillis
                )
            }

            else -> {
                app.logRepository.append("ERROR", "统一执行器收到未知action=$action")
                null
            }
        }
    }

    private suspend fun executeReserveAction(
        context: Context,
        action: String,
        captcha: String,
        token: String,
        triggerSource: String,
        targetOffsetDays: Long
    ): RunOutcome? {
        val app = context.applicationContext as PreserveSeatApp
        if (!Scheduler.tryConsumeExecutionToken(context, action, token, triggerSource)) {
            app.logRepository.append("INFO", "统一执行器退出：每日任务触发已消费 token=$token source=$triggerSource")
            return null
        }
        val config = app.configStore.getConfig()
        val results = app.reservationEngine.runForTomorrowBatch(captcha)
        val trigger = runCatching { LocalTime.parse(config.triggerTime) }.getOrDefault(LocalTime.of(7, 16))
        Scheduler.scheduleDaily(context, trigger, config.autoEnabled)
        return RunOutcome(title = "每日预约", results = results)
    }

    private suspend fun runPrecheck(
        context: Context,
        action: String,
        captcha: String,
        token: String,
        triggerSource: String,
        targetOffsetDays: Long,
        scheduledTriggerAtMillis: Long
    ): RunOutcome? {
        val app = context.applicationContext as PreserveSeatApp
        val targetAction = Scheduler.ACTION_DAILY
        app.logRepository.append(
            "INFO",
            "定时前30秒会话预检开始：precheckAction=$action targetAction=$targetAction token=$token source=$triggerSource triggerAt=$scheduledTriggerAtMillis"
        )
        val warmup = app.reservationEngine.warmupSession()
        app.logRepository.append(
            "INFO",
            "定时前30秒会话预检结束：validBefore=${warmup.validBefore} refreshed=${warmup.refreshed} ready=${warmup.ready}"
        )
        if (!warmup.ready) {
            app.logRepository.append("ERROR", "会话预检失败，保持原定时触发等待后续通道执行")
            return null
        }
        val now = System.currentTimeMillis()
        if (warmup.refreshed && scheduledTriggerAtMillis > 0L && now >= scheduledTriggerAtMillis) {
            app.logRepository.append(
                "INFO",
                "会话刷新后已过原触发时刻，立刻执行后续预约：targetAction=$targetAction now=$now triggerAt=$scheduledTriggerAtMillis"
            )
            return executeReserveAction(
                context = context,
                action = targetAction,
                captcha = captcha,
                token = token,
                triggerSource = "$triggerSource-immediate-after-refresh",
                targetOffsetDays = 1L
            )
        }
        app.logRepository.append("INFO", "会话预检完成，继续等待原触发时刻执行")
        return null
    }
}
