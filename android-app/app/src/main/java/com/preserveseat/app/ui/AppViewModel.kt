package com.preserveseat.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.preserveseat.app.PreserveSeatApp
import com.preserveseat.app.data.model.AppConfig
import com.preserveseat.app.data.model.OccupyBlock
import com.preserveseat.app.data.model.ReservationResult
import com.preserveseat.app.data.model.RoomOption
import com.preserveseat.app.data.model.SeatOption
import com.preserveseat.app.data.model.TimeRangeConfig
import com.preserveseat.app.domain.ReservationResultPipeline
import com.preserveseat.app.domain.ScheduleValidator
import com.preserveseat.app.worker.Scheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val app = application as PreserveSeatApp
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
            app.logRepository.append("INFO", "自动保存基础配置")
            app.configStore.save(config)
            requestDebouncedScheduleSync(config)
            _uiState.value = _uiState.value.copy(
                config = config,
                weekServiceStates = buildWeekServiceStates(config),
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
            val cfg = _uiState.value.config
            if (cfg.token.isBlank() || cfg.cookieHeader.isBlank()) {
                _uiState.value = _uiState.value.copy(toast = "正在登录获取会话，首次获取通常需要较长时间，请耐心等待")
            }
            app.logRepository.append("INFO", "登录后开始刷新当前选择并依次查询占用")
            refreshRoomAndSeatOptionsInternal()
            refreshBlocks()
        }
    }

    private suspend fun refreshRoomAndSeatOptionsInternal() {
            _uiState.value = _uiState.value.copy(loadingOptions = true)
            try {
                val rooms = app.reservationEngine.queryRoomOptions()
                val currentConfig = _uiState.value.config
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
            val selectedSeat = _uiState.value.seatOptions.firstOrNull { it.devId == cfg.seatDevId }
            val updated = cfg.copy(weekSchedule = cfg.weekSchedule.toMutableMap().apply {
                put(day, current + createDefaultSlot(selectedSeat))
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
            val selectedSeat = _uiState.value.seatOptions.firstOrNull { it.devId == cfg.seatDevId }
            val filtered = current.filterNot { it.id == slotId }.ifEmpty { listOf(createDefaultSlot(selectedSeat)) }
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
                val hasAnyChannel = dailyState.alarmEnabled || dailyState.workEnabled || dailyState.jobEnabled
                if (!hasAnyChannel) {
                    val trigger = runCatching { LocalTime.parse(config.triggerTime) }.getOrDefault(LocalTime.of(7, 16))
                    runCatching { Scheduler.scheduleDaily(getApplication(), trigger, true) }
                    app.logRepository.append("INFO", "监测发现每日调度通道全部关闭，已自动重建")
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
            delay(10_000)
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
                app.logRepository.append("INFO", "定时服务防抖更新完成（10s窗口）")
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

    private fun createDefaultSlot(seatRule: SeatOption?): TimeRangeConfig {
        if (seatRule == null) return TimeRangeConfig()
        val openStart = runCatching { LocalTime.parse(seatRule.openStart.take(5)) }.getOrDefault(LocalTime.of(8, 30))
        val openEnd = runCatching { LocalTime.parse(seatRule.openEnd.take(5)) }.getOrDefault(LocalTime.of(22, 15))
        val maxEnd = openStart.plusMinutes(seatRule.maxResvMinutes.toLong())
        val end = if (maxEnd.isBefore(openEnd)) maxEnd else openEnd
        return TimeRangeConfig(
            start = openStart.format(DateTimeFormatter.ofPattern("HH:mm")),
            end = end.format(DateTimeFormatter.ofPattern("HH:mm")),
            enabled = false
        )
    }
}
