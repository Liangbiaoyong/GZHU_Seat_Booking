package com.gzhu.seatbooking.app.worker

import android.content.Context
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import com.gzhu.seatbooking.app.data.model.AppConfig
import com.gzhu.seatbooking.app.data.model.ReservationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.LocalDateTime
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
        val app = context.applicationContext as GzhuSeatBookingApp
        return when (action) {
            Scheduler.ACTION_DAILY -> executeReserveAction(
                context = context,
                action = action,
                captcha = captcha,
                token = token,
                triggerSource = triggerSource,
                targetOffsetDays = targetOffsetDays,
                scheduledTriggerAtMillis = scheduledTriggerAtMillis
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

            SurvivalMonitor.ACTION_SURVIVAL -> {
                runSurvivalMonitor(
                    context = context,
                    action = action,
                    token = token,
                    triggerSource = triggerSource
                )
                null
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
        targetOffsetDays: Long,
        scheduledTriggerAtMillis: Long
    ): RunOutcome? {
        val app = context.applicationContext as GzhuSeatBookingApp
        val config = app.configStore.getConfig()
        val trigger = runCatching { LocalTime.parse(config.triggerTime) }.getOrDefault(LocalTime.of(7, 15))

        val now = System.currentTimeMillis()
        if (scheduledTriggerAtMillis > now + 1_500L) {
            val delayMs = scheduledTriggerAtMillis - now
            val deferred = Scheduler.enqueueDeferredDispatch(
                context = context,
                action = action,
                captcha = captcha,
                token = token,
                triggerSource = triggerSource,
                targetOffsetDays = targetOffsetDays,
                scheduledTriggerAtMillis = scheduledTriggerAtMillis,
                delayMs = delayMs
            )
            if (deferred) {
                app.logRepository.append(
                    "INFO",
                    "执行闸门改为重投递模式：当前不阻塞等待 action=$action source=$triggerSource token=$token delayMs=$delayMs"
                )
                return null
            }
            app.logRepository.append(
                "WARN",
                "执行闸门重投递失败，回退为当前执行 action=$action source=$triggerSource token=$token"
            )
        }

        if (!Scheduler.tryConsumeExecutionToken(context, action, token, triggerSource)) {
            val status = runCatching { Scheduler.queryServiceStatus(context) }.getOrNull()
            val health = status?.let { Scheduler.evaluateDailyHealth(it) }
            if (config.autoEnabled && health != null && !health.allReady) {
                app.logRepository.append(
                    "WARN",
                    "去重命中且通道缺失：为避免取消运行中Work，跳过即时重建 missing=${health.missing.joinToString(",")}" 
                )
            }
            app.logRepository.append(
                "INFO",
                "统一执行器去重命中：已消费 token=$token source=$triggerSource，跳过本通道重建以避免中断正在执行的任务"
            )
            return null
        }
        app.logRepository.append("INFO", "统一执行器开始执行每日预约：source=$triggerSource token=$token")
        val results = mutableListOf<ReservationResult>()
        var stage = "start"
        try {
            stage = "dns-retry"
            results += runTomorrowBatchWithDnsRecovery(app, captcha, triggerSource, token)

            if (shouldRunBurstProbe(results)) {
                stage = "burst-probe"
                app.logRepository.append("INFO", "命中抢占条件（无可执行/暂不可预定），启动5秒高频抢占")
                val deadline = System.currentTimeMillis() + 5_000L
                var rounds = 0
                var lastProbeFailure: ReservationResult? = null
                var probeHitSuccess = false

                while (System.currentTimeMillis() < deadline) {
                    rounds += 1
                    val probe = app.reservationEngine.runForTomorrowFastProbe(captcha)
                    val probeSuccesses = probe.filter { it.success }
                    if (probeSuccesses.isNotEmpty()) {
                        results += probeSuccesses
                        probeHitSuccess = true
                        app.logRepository.append("SUCCESS", "5秒高频抢占命中成功，round=$rounds")
                        break
                    }

                    lastProbeFailure = summarizeProbeFailure(rounds, probe, config)
                    delay(200L)
                }

                if (!probeHitSuccess && lastProbeFailure != null) {
                    // 仅保留本轮5秒探测的最后一次失败摘要，避免结果区出现过多重复失败明细。
                    results += lastProbeFailure
                }
                app.logRepository.append("INFO", "5秒高频抢占结束，rounds=$rounds")
            }
            stage = "done"
        } catch (cancelled: CancellationException) {
            app.logRepository.append(
                "WARN",
                "统一执行器被取消：stage=$stage action=$action source=$triggerSource token=$token message=${cancelled.message.orEmpty()}"
            )
            return null
        } catch (throwable: Throwable) {
            val failed = ReservationResult(
                success = false,
                message = "每日预约执行异常：${throwable.message.orEmpty()}",
                requestAt = LocalDateTime.now(),
                date = LocalDate.now().plusDays(1).toString(),
                seatCode = config.seatCode,
                code = -1
            )
            results += failed
            app.logRepository.append("ERROR", "统一执行器异常：source=$triggerSource token=$token ${failed.message}")
        } finally {
            Scheduler.finishExecution(context, token, triggerSource)
            app.logRepository.append("INFO", "每日预约执行结束：任务数=${results.size}")
            runCatching { Scheduler.scheduleDaily(context, trigger, config.autoEnabled) }
                .onFailure { app.logRepository.append("ERROR", "每日预约结束后重建通道失败：${it.message.orEmpty()}") }
        }
        return RunOutcome(title = "每日预约", results = results)
    }

    private fun summarizeProbeFailure(round: Int, probe: List<ReservationResult>, config: AppConfig): ReservationResult {
        val latestFailure = probe.lastOrNull { !it.success }
        if (latestFailure != null) {
            return latestFailure.copy(
                message = "5秒高频探测第${round}轮失败摘要：${latestFailure.message}",
                requestAt = LocalDateTime.now(),
                code = if (latestFailure.code == 0) -1 else latestFailure.code
            )
        }
        return ReservationResult(
            success = false,
            message = "5秒高频探测第${round}轮失败摘要：本轮无可用结果",
            requestAt = LocalDateTime.now(),
            date = LocalDate.now().plusDays(1).toString(),
            seatCode = config.seatCode,
            code = -1
        )
    }

    private fun shouldRunBurstProbe(results: List<ReservationResult>): Boolean {
        if (results.isEmpty()) return true
        return results.any {
            val msg = it.message.lowercase()
            msg.contains("无可执行预约") ||
                msg.contains("无可执行预约时段") ||
                msg.contains("暂不可预定") ||
                msg.contains("未到预约") ||
                msg.contains("未开放") ||
                msg.contains("不可在当前时间") ||
                msg.contains("当前时间") ||
                msg.contains("可预约时间") ||
                msg.contains("后开始预约") ||
                msg.contains("不在预约") ||
                msg.contains("不可预约")
        }
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
        val app = context.applicationContext as GzhuSeatBookingApp
        val targetAction = Scheduler.ACTION_DAILY
        app.logRepository.append(
            "INFO",
            "定时前1分钟会话预检开始：precheckAction=$action targetAction=$targetAction token=$token source=$triggerSource triggerAt=$scheduledTriggerAtMillis"
        )
        val warmup = app.reservationEngine.warmupSession()
        app.logRepository.append(
            "INFO",
            "定时前1分钟会话预检结束：validBefore=${warmup.validBefore} refreshed=${warmup.refreshed} ready=${warmup.ready}"
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
                targetOffsetDays = 1L,
                scheduledTriggerAtMillis = scheduledTriggerAtMillis
            )
        }
        app.logRepository.append("INFO", "会话预检完成，继续等待原触发时刻执行")
        return null
    }

    private suspend fun runSurvivalMonitor(
        context: Context,
        action: String,
        token: String,
        triggerSource: String
    ) {
        val app = context.applicationContext as GzhuSeatBookingApp
        val config = app.configStore.getConfig()
        if (!config.survivalNotifyEnabled) {
            app.logRepository.append("INFO", "存活监测触发时配置为关闭，跳过通知 action=$action source=$triggerSource")
            runCatching { SurvivalMonitor.updateSchedule(context, false, config.survivalNotifyTime) }
            return
        }

        if (!SurvivalMonitor.tryConsumeToken(context, token)) {
            app.logRepository.append("INFO", "存活监测去重命中，跳过重复通知 token=$token source=$triggerSource")
            return
        }

        val status = runCatching { Scheduler.queryServiceStatus(context) }.getOrNull()
        val daily = status?.daily
        ReserveNotifier.notifySurvivalStatus(
            context = context,
            triggerSource = triggerSource,
            alarmEnabled = daily?.alarmEnabled == true,
            workEnabled = daily?.workEnabled == true,
            jobEnabled = daily?.jobEnabled == true,
            notifyTime = config.survivalNotifyTime
        )

        app.logRepository.append(
            "INFO",
            "存活监测执行完成：alarm=${daily?.alarmEnabled == true} work=${daily?.workEnabled == true} job=${daily?.jobEnabled == true} source=$triggerSource"
        )

        runCatching {
            SurvivalMonitor.updateSchedule(context, config.survivalNotifyEnabled, config.survivalNotifyTime)
        }.onFailure {
            app.logRepository.append("ERROR", "存活监测执行后重建通知失败：${it.message.orEmpty()}")
        }
    }

    private suspend fun runTomorrowBatchWithDnsRecovery(
        app: GzhuSeatBookingApp,
        captcha: String,
        triggerSource: String,
        token: String
    ): List<ReservationResult> {
        val retryIntervalMs = 5_000L
        val retryDeadline = System.currentTimeMillis() + 5 * 60_000L
        var attempt = 1
        var firstFailureAt = 0L

        while (true) {
            try {
                val results = app.reservationEngine.runForTomorrowBatch(captcha)
                if (attempt > 1) {
                    val recoveredMs = System.currentTimeMillis() - firstFailureAt
                    app.logRepository.append(
                        "SUCCESS",
                        "域名解析已恢复，继续原业务：source=$triggerSource token=$token attempts=$attempt recoveredMs=$recoveredMs"
                    )
                }
                return results
            } catch (cancelled: CancellationException) {
                app.logRepository.append(
                    "WARN",
                    "DNS重试阶段任务被取消：source=$triggerSource token=$token attempt=$attempt message=${cancelled.message.orEmpty()}"
                )
                throw cancelled
            } catch (throwable: Throwable) {
                if (!isHostResolveFailure(throwable)) {
                    throw throwable
                }
                if (firstFailureAt == 0L) {
                    firstFailureAt = System.currentTimeMillis()
                    app.logRepository.append(
                        "WARN",
                        "检测到域名解析失败，进入5秒重试，最长5分钟：source=$triggerSource token=$token message=${throwable.message.orEmpty()}"
                    )
                }

                val now = System.currentTimeMillis()
                if (now >= retryDeadline) {
                    app.logRepository.append(
                        "ERROR",
                        "域名解析重试超时（5分钟），结束等待：source=$triggerSource token=$token attempts=$attempt"
                    )
                    throw throwable
                }

                val remainMs = (retryDeadline - now).coerceAtLeast(0L)
                app.logRepository.append(
                    "WARN",
                    "域名解析重试第${attempt}次失败，5秒后重试：source=$triggerSource token=$token remainMs=$remainMs message=${throwable.message.orEmpty()}"
                )
                attempt += 1
                delay(retryIntervalMs)
            }
        }
    }

    private fun isHostResolveFailure(throwable: Throwable): Boolean {
        if (throwable is UnknownHostException) return true
        val message = throwable.message.orEmpty().lowercase()
        if (message.contains("unable to resolve host")) return true
        if (message.contains("no address associated with hostname")) return true
        return throwable.cause?.let { isHostResolveFailure(it) } == true
    }
}

