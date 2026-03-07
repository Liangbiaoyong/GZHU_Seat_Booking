package com.gzhu.seatbooking.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import com.gzhu.seatbooking.app.data.model.AppConfig
import com.gzhu.seatbooking.app.data.model.OccupyBlock
import com.gzhu.seatbooking.app.data.model.ReservationResult
import com.gzhu.seatbooking.app.data.model.RoomOption
import com.gzhu.seatbooking.app.data.model.SeatOption
import com.gzhu.seatbooking.app.data.model.TimeRangeConfig
import com.gzhu.seatbooking.app.data.model.buildDefaultSlotByIndex
import com.gzhu.seatbooking.app.domain.ReservationResultPipeline
import com.gzhu.seatbooking.app.domain.ScheduleValidator
import com.gzhu.seatbooking.app.worker.Scheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class WeekServiceState {
    DISABLED,
    ENABLED,
    FAILED
}

data class TriggerServiceUiState(
    val alarmEnabled: Boolean = false,
    val workEnabled: Boolean = false,
    val jobEnabled: Boolean = false,
    val nextTriggerText: String = "--"
)

data class UiState(
    val config: AppConfig = AppConfig(),
    val roomOptions: List<RoomOption> = emptyList(),
    val seatOptions: List<SeatOption> = emptyList(),
    val loadingOptions: Boolean = false,
    val todayBlocks: List<OccupyBlock> = emptyList(),
    val tomorrowBlocks: List<OccupyBlock> = emptyList(),
    val successResults: List<ReservationResult> = emptyList(),
    val failResults: List<ReservationResult> = emptyList(),
    val serviceEnabledInSystem: Boolean = false,
    val serviceMonitorText: String = "未检测",
    val dailyServiceState: TriggerServiceUiState = TriggerServiceUiState(),
    val weekServiceStates: Map<DayOfWeek, WeekServiceState> = DayOfWeek.entries.associateWith { WeekServiceState.DISABLED },
    val todayLogs: List<String> = emptyList(),
    val toast: String = ""
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val LOG_EXPORT_CHANNEL_ID = "log_export_channel"
        private const val LOG_EXPORT_CHANNEL_NAME = "日志导出通知"
    }

    private val app = application as GzhuSeatBookingApp
    private var scheduleSyncJob: Job? = null
    private var scheduleMutationVersion: Long = 0L

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            app.configStore.configFlow.collect { config ->
                _uiState.value = _uiState.value.copy(
                    config = config,
                    weekServiceStates = buildWeekServiceStates(config)
                )
                refreshServiceMonitor()
            }
        }
        refreshRoomAndSeatOptions()
        refreshBlocks()
        refreshResultPanels()
        refreshLogs()
        refreshServiceMonitor()
    }

    fun updateBasicConfig(config: AppConfig) {
        viewModelScope.launch {
            val current = app.configStore.getConfig()
            val effectiveConfig = config
            if (effectiveConfig == current) {
                _uiState.value = _uiState.value.copy(
                    config = effectiveConfig,
                    weekServiceStates = buildWeekServiceStates(effectiveConfig)
                )
                return@launch
            }
            app.logRepository.append("INFO", "自动保存基础配置")
            app.configStore.save(effectiveConfig)
            val triggerNow = runCatching { LocalTime.parse(effectiveConfig.triggerTime) }.getOrNull()
            if (triggerNow != null && (current.autoEnabled != effectiveConfig.autoEnabled || current.triggerTime != effectiveConfig.triggerTime)) {
                runCatching { Scheduler.scheduleDaily(getApplication(), triggerNow, effectiveConfig.autoEnabled) }
                    .onSuccess {
                        app.logRepository.append("INFO", "定时服务即时更新完成（开关/时间变更）")
                    }
                    .onFailure {
                        app.logRepository.append("ERROR", "定时服务即时更新失败：${it.message.orEmpty()}")
                    }

                val justEnabled = !current.autoEnabled && effectiveConfig.autoEnabled
                if (justEnabled) {
                    if (LocalTime.now().isAfter(triggerNow)) {
                        // Enabling after trigger time should schedule the next day normally.
                        app.logRepository.append("INFO", "启用自动预约时已过触发时刻：本次按下一日正常调度，不触发补偿执行")
                    }
                }
            }
            requestDebouncedScheduleSync(effectiveConfig)
            _uiState.value = _uiState.value.copy(
                config = effectiveConfig,
                weekServiceStates = buildWeekServiceStates(effectiveConfig),
                toast = "配置已自动保存"
            )
            refreshLogs()
            refreshServiceMonitor()
        }
    }

    fun runManual() {
        runManualByOffset(1L, "下一天")
    }

    fun runManualToday() {
        runManualByOffset(0L, "今天")
    }

    private fun runManualByOffset(offsetDays: Long, label: String) {
        viewModelScope.launch {
            app.logRepository.append("INFO", "用户触发手动预约$label")
            val results = app.reservationEngine.runForOffsetBatch(offsetDays)
            val summary = ReservationResultPipeline.record(app, "manual-$label", "手动预约$label", results)
            refreshResultPanels()
            _uiState.value = _uiState.value.copy(
                toast = "手动预约${label}完成：成功${summary.successCount}，失败${summary.failCount}"
            )
            app.logRepository.append("INFO", "手动预约${label}结束：成功${summary.successCount}，失败${summary.failCount}")
            refreshBlocks()
            refreshLogs()
        }
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = "")
    }

    fun refreshBlocks() {
        viewModelScope.launch {
            app.logRepository.append("INFO", "开始刷新今日/明日占用信息")
            try {
                val today = LocalDate.now()
                val tomorrow = today.plusDays(1)
                app.logRepository.append("INFO", "占用刷新进度：开始查询今日 $today")
                val todayBlocks = app.reservationEngine.queryOccupyFor(today)
                app.logRepository.append("INFO", "占用刷新进度：开始查询明日 $tomorrow")
                val tomorrowBlocks = app.reservationEngine.queryOccupyFor(tomorrow)
                _uiState.value = _uiState.value.copy(todayBlocks = todayBlocks, tomorrowBlocks = tomorrowBlocks)
                app.logRepository.append("INFO", "占用信息刷新完成：今日${todayBlocks.size}条，明日${tomorrowBlocks.size}条")
            } catch (throwable: Throwable) {
                app.logRepository.append("ERROR", "占用刷新异常：${throwable.message.orEmpty()}")
            }
        }
    }

    fun refreshRoomAndSeatOptions() {
        viewModelScope.launch {
            refreshRoomAndSeatOptionsInternal()
        }
    }

    fun loginAndFetchSession() {
        viewModelScope.launch {
            val sessionValid = app.reservationEngine.isSessionValid()
            if (sessionValid) {
                _uiState.value = _uiState.value.copy(toast = "已经登录成功")
                app.logRepository.append("INFO", "登录按钮触发：当前会话有效，无需重新获取")
            } else {
                _uiState.value = _uiState.value.copy(toast = "正在登录获取会话，首次获取通常需要较长时间，请耐心等待")
                app.logRepository.append("INFO", "登录按钮触发：会话无效，准备重新获取")
            }
            app.logRepository.append("INFO", "登录后开始刷新当前选择并依次查询占用")
            refreshRoomAndSeatOptionsInternal()
            refreshBlocks()
        }
    }

    fun clearSessionForReloginTest() {
        viewModelScope.launch {
            val current = app.configStore.getConfig()
            val cleared = current.copy(token = "", cookieHeader = "")
            app.configStore.save(cleared)
            _uiState.value = _uiState.value.copy(config = cleared, toast = "已退出登录并清除会话")
            app.logRepository.append("INFO", "用户触发退出登录：已清除token/cookie，用于自动重登录测试")
            refreshLogs()
        }
    }

    private suspend fun refreshRoomAndSeatOptionsInternal() {
            _uiState.value = _uiState.value.copy(loadingOptions = true)
            try {
                val rooms = app.reservationEngine.queryRoomOptions()
                // Always read the latest persisted config to avoid overwriting activation/auto flags
                // with the default UiState during app cold start.
                val currentConfig = app.configStore.getConfig()
                val selectedRoom = rooms.firstOrNull { it.roomId == currentConfig.roomId } ?: rooms.firstOrNull()
                val roomId = selectedRoom?.roomId ?: currentConfig.roomId
                val seats = app.reservationEngine.querySeatOptions(roomId)
                val selectedSeat = seats.firstOrNull { it.devId == currentConfig.seatDevId }
                    ?: seats.firstOrNull { it.seatCode == currentConfig.seatCode }
                    ?: seats.firstOrNull()

                val updatedConfig = currentConfig.copy(
                    roomId = roomId,
                    roomName = selectedRoom?.roomName ?: currentConfig.roomName,
                    seatDevId = selectedSeat?.devId ?: currentConfig.seatDevId,
                    seatCode = selectedSeat?.seatCode ?: currentConfig.seatCode
                )
                app.configStore.save(updatedConfig)

                _uiState.value = _uiState.value.copy(
                    config = updatedConfig,
                    roomOptions = rooms,
                    seatOptions = seats,
                    loadingOptions = false
                )
                requestDebouncedScheduleSync(updatedConfig)
            } catch (throwable: Throwable) {
                app.logRepository.append("ERROR", "房间/座位选项刷新失败：${throwable.message.orEmpty()}")
                _uiState.value = _uiState.value.copy(loadingOptions = false)
            }
            refreshLogs()
    }

    fun selectRoom(roomId: Int) {
        viewModelScope.launch {
            val room = _uiState.value.roomOptions.firstOrNull { it.roomId == roomId } ?: return@launch
            val seats = app.reservationEngine.querySeatOptions(roomId)
            val chosenSeat = seats.firstOrNull()
            val cfg = _uiState.value.config.copy(
                roomId = room.roomId,
                roomName = room.roomName,
                seatDevId = chosenSeat?.devId ?: 0,
                seatCode = chosenSeat?.seatCode ?: ""
            )
            app.configStore.save(cfg)
            _uiState.value = _uiState.value.copy(config = cfg, seatOptions = seats)
            requestDebouncedScheduleSync(cfg)
            refreshBlocks()
        }
    }

    fun selectSeat(devId: Int) {
        viewModelScope.launch {
            val seat = _uiState.value.seatOptions.firstOrNull { it.devId == devId } ?: return@launch
            val cfg = _uiState.value.config.copy(
                seatDevId = seat.devId,
                seatCode = seat.seatCode
            )
            app.configStore.save(cfg)
            _uiState.value = _uiState.value.copy(config = cfg)
            requestDebouncedScheduleSync(cfg)
            refreshBlocks()
        }
    }

    fun refreshLogs() {
        viewModelScope.launch {
            val logs = app.logRepository.todayLogs().map { "${it.timestamp} [${it.level}] ${it.message}" }
            _uiState.value = _uiState.value.copy(todayLogs = logs)
        }
    }

    fun exportLogsZip() {
        viewModelScope.launch {
            val zipFile = app.logRepository.exportLogsZip()
            if (zipFile == null) {
                _uiState.value = _uiState.value.copy(toast = "当前没有可导出的日志")
                return@launch
            }
            val path = zipFile.absolutePath
            _uiState.value = _uiState.value.copy(toast = "日志已导出：$path")
            notifyLogExportPath(path, zipFile)
            app.logRepository.append("SUCCESS", "日志导出完成：$path")
            refreshLogs()
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            app.logRepository.clearAllLogs()
            _uiState.value = _uiState.value.copy(todayLogs = emptyList(), toast = "运行日志已清除")
        }
    }

    fun refreshResultPanels() {
        viewModelScope.launch {
            val all = app.reservationResultRepository.listAll()
            _uiState.value = _uiState.value.copy(
                successResults = all.filter { it.success },
                failResults = all.filterNot { it.success }
            )
            app.logRepository.append(
                "INFO",
                "刷新成功/失败记录区：success=${all.count { it.success }} fail=${all.count { !it.success }}"
            )
        }
    }

    fun updateWeekSlot(day: DayOfWeek, slotId: String, start: String, end: String, enabled: Boolean) {
        viewModelScope.launch {
            val cfg = _uiState.value.config
            val current = cfg.weekSchedule[day].orEmpty()
            val replaced = current.map {
                if (it.id == slotId) it.copy(start = start, end = end, enabled = enabled) else it
            }
            val updated = cfg.copy(weekSchedule = cfg.weekSchedule.toMutableMap().apply { put(day, replaced) })
            app.configStore.save(updated)
            _uiState.value = _uiState.value.copy(config = updated, weekServiceStates = buildWeekServiceStates(updated))
            requestDebouncedScheduleSync(updated)
            refreshServiceMonitor()
        }
    }

    fun addWeekSlot(day: DayOfWeek) {
        viewModelScope.launch {
            val cfg = _uiState.value.config
            val current = cfg.weekSchedule[day].orEmpty()
            val updated = cfg.copy(weekSchedule = cfg.weekSchedule.toMutableMap().apply {
                put(day, current + createDefaultSlot(current.size))
            })
            app.configStore.save(updated)
            _uiState.value = _uiState.value.copy(config = updated, weekServiceStates = buildWeekServiceStates(updated))
            requestDebouncedScheduleSync(updated)
            refreshServiceMonitor()
        }
    }

    fun removeWeekSlot(day: DayOfWeek, slotId: String) {
        viewModelScope.launch {
            val cfg = _uiState.value.config
            val current = cfg.weekSchedule[day].orEmpty()
            val filtered = current.filterNot { it.id == slotId }.ifEmpty { listOf(createDefaultSlot(0)) }
            val updated = cfg.copy(weekSchedule = cfg.weekSchedule.toMutableMap().apply { put(day, filtered) })
            app.configStore.save(updated)
            _uiState.value = _uiState.value.copy(config = updated, weekServiceStates = buildWeekServiceStates(updated))
            requestDebouncedScheduleSync(updated)
            refreshServiceMonitor()
        }
    }

    private fun buildWeekServiceStates(config: AppConfig): Map<DayOfWeek, WeekServiceState> {
        if (!config.autoEnabled) {
            return DayOfWeek.entries.associateWith { WeekServiceState.DISABLED }
        }
        return DayOfWeek.entries.associateWith { day ->
            val enabledItems = config.weekSchedule[day].orEmpty().filter { it.enabled }
            if (enabledItems.isEmpty()) {
                WeekServiceState.DISABLED
            } else {
                val hasError = enabledItems.any { ScheduleValidator.validate(it.start, it.end) != null }
                if (hasError) WeekServiceState.FAILED else WeekServiceState.ENABLED
            }
        }
    }

    private fun refreshServiceMonitor() {
        viewModelScope.launch {
            val config = _uiState.value.config
            val schedulerStatus = runCatching { Scheduler.queryServiceStatus(getApplication()) }.getOrNull()
            val dailyState = schedulerStatus?.daily
            val serviceEnabled = config.autoEnabled && Scheduler.isDailyTaskEnabled(getApplication())
            val dailyUiRaw = dailyState?.let {
                TriggerServiceUiState(
                    alarmEnabled = it.alarmEnabled,
                    workEnabled = it.workEnabled,
                    jobEnabled = it.jobEnabled,
                    nextTriggerText = formatTriggerMillis(it.nextTriggerMillis)
                )
            } ?: TriggerServiceUiState()
            val dailyUi = if (config.autoEnabled) dailyUiRaw else TriggerServiceUiState()

            _uiState.value = _uiState.value.copy(
                serviceEnabledInSystem = serviceEnabled,
                serviceMonitorText = if (serviceEnabled) "每日预约任务已在系统启用" else "每日预约任务未启动",
                dailyServiceState = dailyUi,
                weekServiceStates = buildWeekServiceStates(config)
            )

            app.logRepository.append(
                "INFO",
                "监测刷新：auto=${config.autoEnabled} enabled=$serviceEnabled alarm=${dailyUi.alarmEnabled} work=${dailyUi.workEnabled} job=${dailyUi.jobEnabled} next=${dailyUi.nextTriggerText}"
            )

            if (config.autoEnabled && dailyState != null) {
                val now = System.currentTimeMillis()
                val health = Scheduler.evaluateDailyHealth(schedulerStatus)
                if (!health.allReady) {
                    val trigger = runCatching { LocalTime.parse(config.triggerTime) }.getOrDefault(LocalTime.of(7, 15))
                    runCatching { Scheduler.scheduleDaily(getApplication(), trigger, true) }
                    app.logRepository.append("WARN", "监测发现每日调度通道缺失(${health.missing.joinToString(",")})，已自动重建")
                } else if (dailyState.nextTriggerMillis > 0L && now - dailyState.nextTriggerMillis > 3 * 60 * 1000L) {
                    Scheduler.triggerDailyCatchUp(getApplication(), "monitor-overdue")
                    app.logRepository.append("INFO", "监测发现每日任务超过触发时间未执行，已触发补偿执行")
                }
            }
        }
    }

    private fun requestDebouncedScheduleSync(config: AppConfig) {
        scheduleMutationVersion += 1
        val currentVersion = scheduleMutationVersion
        scheduleSyncJob?.cancel()
        scheduleSyncJob = viewModelScope.launch {
            delay(2_000)
            if (currentVersion != scheduleMutationVersion) return@launch
            val trigger = runCatching { LocalTime.parse(config.triggerTime) }.getOrNull()
            if (trigger == null) {
                app.logRepository.append("ERROR", "定时服务防抖更新跳过：启动时间格式无效(${config.triggerTime})，请修正为HH:mm")
                refreshServiceMonitor()
                return@launch
            }
            val scheduleError = runCatching {
                Scheduler.scheduleDaily(getApplication(), trigger, config.autoEnabled)
            }.exceptionOrNull()
            if (scheduleError != null) {
                app.logRepository.append("ERROR", "定时服务防抖更新失败：${scheduleError.message.orEmpty()}")
            } else {
                app.logRepository.append("INFO", "定时服务防抖更新完成（2s窗口）")
            }
            refreshServiceMonitor()
        }
    }

    private fun formatTriggerMillis(millis: Long): String {
        if (millis <= 0L) return "--"
        return runCatching {
            java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
        }.getOrDefault("--")
    }

    private fun createDefaultSlot(index: Int): TimeRangeConfig {
        return buildDefaultSlotByIndex(index)
    }

    private fun notifyLogExportPath(path: String, zipFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                app.logRepository.append("WARN", "导出日志通知跳过：缺少通知权限")
                return
            }
        }

        val manager = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureExportChannel(manager)
        val message = "导出地址：$path"
        val notification = NotificationCompat.Builder(getApplication(), LOG_EXPORT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("日志导出成功")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notifyId = (zipFile.lastModified() % Int.MAX_VALUE).toInt().coerceAtLeast(1)
        manager.notify(notifyId, notification)
    }

    private fun ensureExportChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LOG_EXPORT_CHANNEL_ID,
                LOG_EXPORT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }
}

