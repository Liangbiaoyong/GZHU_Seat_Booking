package com.preserveseat.app.domain

import com.preserveseat.app.data.local.ConfigStore
import com.preserveseat.app.data.local.LogRepository
import com.preserveseat.app.data.model.AppConfig
import com.preserveseat.app.data.model.OccupyBlock
import com.preserveseat.app.data.model.ReservationRequest
import com.preserveseat.app.data.model.ReservationResult
import com.preserveseat.app.data.model.RoomOption
import com.preserveseat.app.data.model.SeatOption
import com.preserveseat.app.data.network.LibraryApi
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ReservationEngine(
    private val configStore: ConfigStore,
    private val logRepository: LogRepository,
    private val api: LibraryApi
) {
    data class SessionWarmupResult(
        val validBefore: Boolean,
        val refreshed: Boolean,
        val ready: Boolean
    )

    private val retryableKeywords = listOf("请求频繁", "操作频繁", "请稍后", "too many", "too frequent", "rate")
    private val alreadyReservedKeywords = listOf("有预约", "已预约")

    suspend fun runForTomorrow(manualCaptcha: String = ""): ReservationResult {
        val results = runForTomorrowBatch(manualCaptcha)
        if (results.isEmpty()) {
            return ReservationResult(false, "未配置可执行的预约时段", LocalDateTime.now())
        }
        val successCount = results.count { it.success }
        val failCount = results.size - successCount
        val message = "共${results.size}个任务，成功${successCount}，失败${failCount}"
        return ReservationResult(successCount > 0, message, LocalDateTime.now())
    }

    suspend fun runForTodayBatch(manualCaptcha: String = ""): List<ReservationResult> {
        return runForDateBatch(LocalDate.now(), manualCaptcha)
    }

    suspend fun runForOffsetBatch(offsetDays: Long, manualCaptcha: String = ""): List<ReservationResult> {
        val targetDate = LocalDate.now().plusDays(offsetDays)
        return runForDateBatch(targetDate, manualCaptcha)
    }

    suspend fun runForTomorrowBatch(manualCaptcha: String = ""): List<ReservationResult> {
        return runForDateBatch(LocalDate.now().plusDays(1), manualCaptcha)
    }

    suspend fun runForDateBatch(targetDate: LocalDate, manualCaptcha: String = ""): List<ReservationResult> {
        logRepository.append("INFO", "开始执行手动预约流程")
        val config = configStore.getConfig()
        val dayConfigs = config.weekSchedule[targetDate.dayOfWeek].orEmpty().filter { it.enabled }
        logRepository.append("INFO", "预约上下文：date=$targetDate weekday=${targetDate.dayOfWeek} enabledSlots=${dayConfigs.size} room=${config.roomName} seat=${config.seatCode}")
        if (dayConfigs.isEmpty()) {
            val msg = "${targetDate.dayOfWeek} 未启用预约"
            logRepository.append("INFO", msg)
            return emptyList()
        }

        if (config.seatDevId <= 0) {
            val msg = "请先在房间与座位下拉框中选择有效座位"
            logRepository.append("ERROR", msg)
            return listOf(ReservationResult(false, msg, LocalDateTime.now()))
        }
        logRepository.append("INFO", "座位配置：roomId=${config.roomId} room=${config.roomName} seat=${config.seatCode} devId=${config.seatDevId}")

        val sessionReadyConfig = ensureSession(config)
            ?: return listOf(ReservationResult(false, "账号登录失败，无法自动获取会话", LocalDateTime.now()))
        logRepository.append("INFO", "会话准备完成：tokenLen=${sessionReadyConfig.token.length} cookieLen=${sessionReadyConfig.cookieHeader.length}")

        val results = mutableListOf<ReservationResult>()
        val retryQueue = mutableListOf<ReservationRequest>()
        dayConfigs.forEach { dayConfig ->
            logRepository.append("INFO", "准备预约时段 ${dayConfig.start}-${dayConfig.end}，座位 ${config.seatCode}")
            val validateError = ScheduleValidator.validate(dayConfig.start, dayConfig.end)
            if (validateError != null) {
                logRepository.append("ERROR", validateError)
                results += ReservationResult(false, validateError, LocalDateTime.now())
                return@forEach
            }

            val request = ReservationRequest(
                date = targetDate,
                start = LocalTime.parse(dayConfig.start),
                end = LocalTime.parse(dayConfig.end),
                seatCode = sessionReadyConfig.seatCode,
                seatDevId = sessionReadyConfig.seatDevId,
                captcha = manualCaptcha
            )
            val result = reserve(sessionReadyConfig, request)
            results += result
            if (!result.success && isRetryable(result)) {
                retryQueue += request
                logRepository.append("INFO", "加入重试队列：${request.start}-${request.end}，原因=${result.message}")
            }
        }

        if (retryQueue.isNotEmpty()) {
            logRepository.append("INFO", "检测到请求频繁阻断，开始重试队列，初始任务数=${retryQueue.size}")
            val maxRounds = 8
            val intervalMs = 9000L
            var round = 1
            while (retryQueue.isNotEmpty() && round <= maxRounds) {
                delay(intervalMs)
                logRepository.append("INFO", "重试轮次$round 开始，待处理=${retryQueue.size}")
                val current = retryQueue.toList()
                retryQueue.clear()
                current.forEach { request ->
                    val retried = reserve(sessionReadyConfig, request)
                    results += retried
                    if (!retried.success && isRetryable(retried)) {
                        retryQueue += request
                    }
                }
                logRepository.append("INFO", "重试轮次$round 结束，剩余阻断=${retryQueue.size}")
                round += 1
            }
            if (retryQueue.isNotEmpty()) {
                logRepository.append("ERROR", "重试结束后仍有阻断任务未成功，remaining=${retryQueue.size}")
            } else {
                logRepository.append("SUCCESS", "重试队列已清空，阻断任务已完成")
            }
        }
        logRepository.append("INFO", "手动预约流程结束，任务数=${results.size}")
        return results
    }

    suspend fun reserve(config: AppConfig, request: ReservationRequest): ReservationResult {
        logRepository.append("INFO", "进入预约执行：seat=${request.seatCode} time=${request.start}-${request.end} date=${request.date}")
        if (config.account.isBlank() || config.password.isBlank()) {
            logRepository.append("ERROR", "预约失败：账号或密码为空")
            return ReservationResult(false, "请先配置账号和密码", LocalDateTime.now())
        }

        val effectiveConfig = if (api.validateSession(config.token, config.cookieHeader)) {
            config
        } else {
            logRepository.append("INFO", "预约前会话失效，尝试自动刷新")
            ensureSession(config) ?: run {
                logRepository.append("ERROR", "预约失败：会话失效且自动刷新失败")
                return ReservationResult(false, "会话失效，自动登录刷新失败", LocalDateTime.now())
            }
        }

        val begin = "${request.date} ${request.start}:00"
        val end = "${request.date} ${request.end}:00"
        val userInfoAccNo = api.getUserInfo(effectiveConfig.token, effectiveConfig.cookieHeader)?.accNo
        val accNo = userInfoAccNo
            ?: effectiveConfig.account.toIntOrNull()
            ?: return ReservationResult(false, "无法获取有效 accNo（账号需为数字学工号）", LocalDateTime.now())
        logRepository.append("INFO", "预约参数：begin=$begin end=$end accNoSource=${if (userInfoAccNo != null) "userInfo" else "account"} devId=${request.seatDevId}")
        val (httpCode, body) = api.reserve(
            token = effectiveConfig.token,
            cookieHeader = effectiveConfig.cookieHeader,
            appAccNo = accNo,
            devId = request.seatDevId,
            beginDateTime = begin,
            endDateTime = end,
            captcha = request.captcha
        )

        val json = runCatching { JSONObject(body) }.getOrNull()
        val code = json?.optInt("code", -1) ?: -1
        val message = json?.optString("message").orEmpty().ifBlank { body }
        val normalizedMessage = message.lowercase()
        val success = httpCode == 200 && (code == 0 || alreadyReservedKeywords.any { normalizedMessage.contains(it.lowercase()) })

        val result = ReservationResult(
            success = success,
            message = message,
            requestAt = LocalDateTime.now(),
            date = request.date.toString(),
            code = code,
            raw = body,
            seatCode = request.seatCode,
            start = request.start.format(DateTimeFormatter.ofPattern("HH:mm")),
            end = request.end.format(DateTimeFormatter.ofPattern("HH:mm"))
        )

        val rawShort = result.raw.replace("\n", " ").take(180)
        logRepository.append(
            if (success) "SUCCESS" else "ERROR",
            "预约结果: seat=${result.seatCode} time=${result.start}-${result.end} http=$httpCode code=${result.code} message=${result.message} raw=$rawShort"
        )
        logRepository.cleanupOldLogs()

        val newConfig = effectiveConfig.copy(lastRunAt = LocalDateTime.now().toString())
        configStore.save(newConfig)
        return result
    }

    suspend fun queryOccupyFor(day: LocalDate): List<OccupyBlock> {
        val config = configStore.getConfig()
        if (config.roomId <= 0 || config.seatDevId <= 0) {
            logRepository.append("ERROR", "占用查询失败：请先选择房间和座位")
            return emptyList()
        }
        logRepository.append("INFO", "开始查询占用信息：date=$day room=${config.roomName} seat=${config.seatCode} devId=${config.seatDevId}")
        return try {
            val readyConfig = if (api.validateSession(config.token, config.cookieHeader)) {
                config
            } else {
                logRepository.append("INFO", "占用查询会话失效，尝试自动刷新")
                ensureSession(config) ?: run {
                    logRepository.append("ERROR", "占用查询失败：自动刷新会话失败")
                    return emptyList()
                }
            }
            val blocks = api.queryDayBlocks(
                readyConfig.token,
                readyConfig.cookieHeader,
                day,
                readyConfig.roomId,
                readyConfig.seatDevId,
                readyConfig.seatCode
            )
            logRepository.append("INFO", "占用查询完成：date=$day count=${blocks.size}")
            blocks
        } catch (throwable: Throwable) {
            logRepository.append("ERROR", "占用查询异常：${throwable.message.orEmpty()}")
            emptyList()
        }
    }

    suspend fun queryRoomOptions(): List<RoomOption> {
        val config = configStore.getConfig()
        val readyConfig = ensureSession(config) ?: return emptyList()
        return api.queryRoomTree(readyConfig.token, readyConfig.cookieHeader)
    }

    suspend fun querySeatOptions(roomId: Int, day: LocalDate = LocalDate.now()): List<SeatOption> {
        if (roomId <= 0) return emptyList()
        val config = configStore.getConfig()
        val readyConfig = ensureSession(config) ?: return emptyList()
        return api.queryRoomSeats(readyConfig.token, readyConfig.cookieHeader, roomId, day)
    }

    suspend fun warmupSession(): SessionWarmupResult {
        val config = configStore.getConfig()
        if (api.validateSession(config.token, config.cookieHeader)) {
            logRepository.append("INFO", "会话预检：当前token/cookie有效，无需刷新")
            return SessionWarmupResult(validBefore = true, refreshed = false, ready = true)
        }
        logRepository.append("INFO", "会话预检：当前token/cookie失效，准备自动刷新")
        if (config.account.isBlank() || config.password.isBlank()) {
            logRepository.append("ERROR", "会话预检失败：账号或密码为空，无法刷新")
            return SessionWarmupResult(validBefore = false, refreshed = false, ready = false)
        }
        val refreshed = ensureSession(config) != null
        if (refreshed) {
            logRepository.append("INFO", "会话预检刷新成功")
        } else {
            logRepository.append("ERROR", "会话预检刷新失败")
        }
        return SessionWarmupResult(validBefore = false, refreshed = refreshed, ready = refreshed)
    }

    private suspend fun ensureSession(config: AppConfig): AppConfig? {
        if (api.validateSession(config.token, config.cookieHeader)) {
            logRepository.append("INFO", "检测到已有有效会话，直接执行预约")
            return config
        }
        logRepository.append("INFO", "会话无效，开始账号密码自动登录")
        val session = api.refreshSessionByAccountPassword(config.account, config.password) ?: return null
        val refreshed = config.copy(token = session.token, cookieHeader = session.cookieHeader)
        configStore.save(refreshed)
        logRepository.append("INFO", "已通过账号密码自动刷新会话")
        return refreshed
    }

    private fun isRetryable(result: ReservationResult): Boolean {
        val msg = result.message.lowercase()
        return retryableKeywords.any { msg.contains(it.lowercase()) }
    }
}
