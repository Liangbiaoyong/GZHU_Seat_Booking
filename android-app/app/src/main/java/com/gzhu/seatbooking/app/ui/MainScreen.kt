package com.gzhu.seatbooking.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gzhu.seatbooking.app.data.model.AppConfig
import com.gzhu.seatbooking.app.data.model.OccupyBlock
import com.gzhu.seatbooking.app.data.model.ReservationResult
import com.gzhu.seatbooking.app.data.model.RoomOption
import com.gzhu.seatbooking.app.data.model.SeatOption
import com.gzhu.seatbooking.app.data.model.TimeRangeConfig
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun MainScreen(vm: AppViewModel) {
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.toast) {
        if (state.toast.isNotBlank()) {
            snackbarHostState.showSnackbar(state.toast)
            vm.consumeToast()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) {
            vm.refreshResultPanels()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("设置") }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("状态") }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = { Text("日志") }
                )
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> ConfigTab(
                        config = state.config,
                        activated = state.config.activated,
                        todayBlocks = state.todayBlocks,
                        tomorrowBlocks = state.tomorrowBlocks,
                        roomOptions = state.roomOptions,
                        seatOptions = state.seatOptions,
                        loadingOptions = state.loadingOptions,
                        onBasicConfigChange = vm::updateBasicConfig,
                        onLoginFetchSession = vm::loginAndFetchSession,
                        onLogoutClearSession = vm::clearSessionForReloginTest,
                        onSelectRoom = vm::selectRoom,
                        onSelectSeat = vm::selectSeat,
                        onManualRun = vm::runManual,
                        onManualRunToday = vm::runManualToday,
                        onUpdateWeekSlot = vm::updateWeekSlot,
                        onAddWeekSlot = vm::addWeekSlot,
                        onRemoveWeekSlot = vm::removeWeekSlot,
                        onActivationSubmit = vm::verifyActivationCode,
                        onActivatedClick = vm::notifyAlreadyActivated,
                        onActivationRequiredForAuto = vm::notifyActivationRequiredForAuto
                    )

                    1 -> MonitorTab(
                        successMessages = state.successResults.map(::formatResultLine),
                        failMessages = state.failResults.map(::formatResultLine),
                        serviceEnabled = state.serviceEnabledInSystem,
                        serviceText = state.serviceMonitorText,
                        weekStates = state.weekServiceStates,
                        dailyServiceState = state.dailyServiceState
                    )

                    else -> LogsTab(
                        logs = state.todayLogs,
                        onExportLogs = vm::exportLogsZip,
                        onClearLogs = vm::clearLogs
                    )
                }
            }
        }
    }
}

private fun formatResultLine(result: ReservationResult): String {
    val opTime = result.requestAt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    return "操作时间$opTime | 日期${result.date.ifBlank { "-" }} 座位${result.seatCode} ${result.start}-${result.end} | ${result.message}"
}

@Composable
private fun ConfigTab(
    config: AppConfig,
    activated: Boolean,
    todayBlocks: List<OccupyBlock>,
    tomorrowBlocks: List<OccupyBlock>,
    roomOptions: List<RoomOption>,
    seatOptions: List<SeatOption>,
    loadingOptions: Boolean,
    onBasicConfigChange: (AppConfig) -> Unit,
    onLoginFetchSession: () -> Unit,
    onLogoutClearSession: () -> Unit,
    onSelectRoom: (Int) -> Unit,
    onSelectSeat: (Int) -> Unit,
    onManualRun: () -> Unit,
    onManualRunToday: () -> Unit,
    onUpdateWeekSlot: (DayOfWeek, String, String, String, Boolean) -> Unit,
    onAddWeekSlot: (DayOfWeek) -> Unit,
    onRemoveWeekSlot: (DayOfWeek, String) -> Unit,
    onActivationSubmit: (String) -> Unit,
    onActivatedClick: () -> Unit,
    onActivationRequiredForAuto: () -> Unit
) {
    var account by remember(config.account) { mutableStateOf(config.account) }
    var password by remember(config.password) { mutableStateOf(config.password) }
    var triggerTime by remember(config.triggerTime) { mutableStateOf(config.triggerTime) }
    var roomExpanded by remember { mutableStateOf(false) }
    var seatExpanded by remember { mutableStateOf(false) }
    var showStabilityNotice by remember { mutableStateOf(false) }
    var showActivationDialog by remember { mutableStateOf(false) }
    var activationCodeInput by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val todayDayUpper = LocalDate.now().dayOfWeek.name
    val tomorrowDayUpper = LocalDate.now().plusDays(1).dayOfWeek.name
    val selectedSeat = seatOptions.firstOrNull { it.devId == config.seatDevId }
    val triggerTimeError = validateTimeInput(triggerTime)

    LaunchedEffect(activated) {
        if (activated) {
            showActivationDialog = false
            activationCodeInput = ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("GZHU Seat Booking", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = {
                if (activated) {
                    onActivatedClick()
                } else {
                    showActivationDialog = true
                }
            }) {
                Text(if (activated) "已激活" else "需激活")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启动每日预约任务")
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = config.autoEnabled, onCheckedChange = {
                if (!activated && it) {
                    onActivationRequiredForAuto()
                    return@Switch
                }
                onBasicConfigChange(
                    config.copy(
                        account = account,
                        password = password,
                        autoEnabled = it,
                        triggerTime = triggerTime
                    )
                )
            })
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { showStabilityNotice = true }) {
                Text("稳定运行公告")
            }
        }

        if (showActivationDialog) {
            AlertDialog(
                onDismissRequest = { showActivationDialog = false },
                confirmButton = {
                    TextButton(onClick = {
                        onActivationSubmit(activationCodeInput)
                    }) {
                        Text("检验激活")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        activationCodeInput = ""
                        showActivationDialog = false
                    }) {
                        Text("关闭")
                    }
                },
                title = { Text("激活应用") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("需找开发者提供加密序列。")
                        OutlinedTextField(
                            value = activationCodeInput,
                            onValueChange = { activationCodeInput = it },
                            label = { Text("输入加密序列") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            )
        }

        if (showStabilityNotice) {
            AlertDialog(
                onDismissRequest = { showStabilityNotice = false },
                confirmButton = {
                    TextButton(onClick = { showStabilityNotice = false }) {
                        Text("我知道了")
                    }
                },
                title = { Text("稳定运行建议") },
                text = {
                    Text(
                        "请开启：锁后台、后台无限制、自启动。\n\n" +
                            "为了稳定运行，建议睡前先打开应用并让服务进入前台。\n\n" +
                            "不同品牌系统对后台/定时任务的限制策略不同，可能影响唤醒和执行稳定性。"
                    )
                }
            )
        }

        OutlinedTextField(value = triggerTime, onValueChange = {
            triggerTime = sanitizeTimeInput(it)
            onBasicConfigChange(config.copy(account = account, password = password, autoEnabled = config.autoEnabled, triggerTime = triggerTime))
        }, label = { Text("启动时间(HH:mm)") }, modifier = Modifier.fillMaxWidth(), isError = triggerTimeError != null,
            supportingText = {
                triggerTimeError?.let { Text(it) }
            }
        )
        OutlinedTextField(value = account, onValueChange = {
            account = it
            onBasicConfigChange(config.copy(account = account, password = password, autoEnabled = config.autoEnabled, triggerTime = triggerTime))
        }, label = { Text("广大统一身份认证账户") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = {
            password = it
            onBasicConfigChange(config.copy(account = account, password = password, autoEnabled = config.autoEnabled, triggerTime = triggerTime))
        }, label = { Text("广大统一身份认证密码") }, modifier = Modifier.fillMaxWidth())

        Button(onClick = {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            onLoginFetchSession()
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (loadingOptions) "登录中（获取会话）..." else "登录（获取会话）")
        }

        Button(
            onClick = {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                onLogoutClearSession()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("退出登录（清除会话）")
        }

        val sessionReady = config.token.isNotBlank() && config.cookieHeader.isNotBlank()
        SessionStatusCard(ready = sessionReady)

        RoomDropdown(
            rooms = roomOptions,
            selectedRoomId = config.roomId,
            expanded = roomExpanded,
            onExpandedChange = { roomExpanded = it },
            onSelect = {
                roomExpanded = false
                onSelectRoom(it)
            }
        )

        SeatDropdown(
            seats = seatOptions,
            selectedSeatDevId = config.seatDevId,
            expanded = seatExpanded,
            onExpandedChange = { seatExpanded = it },
            onSelect = {
                seatExpanded = false
                onSelectSeat(it)
            }
        )

        Text("今日占用")
        OccupyChart(todayBlocks)
        Text("明日占用")
        OccupyChart(tomorrowBlocks)

        Text("每周预约时段", style = MaterialTheme.typography.titleSmall)
        CurrentSeatRuleCard(selectedSeat)
        DayOfWeek.entries.forEach { day ->
            WeekDayCard(
                dayText = day.name,
                slots = config.weekSchedule[day].orEmpty(),
                seatRule = selectedSeat,
                onAdd = { onAddWeekSlot(day) },
                onUpdate = { slotId, start, end, enabled -> onUpdateWeekSlot(day, slotId, start, end, enabled) },
                onRemove = { slotId -> onRemoveWeekSlot(day, slotId) }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onManualRunToday, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("手动预约今天($todayDayUpper)")
            }

            Button(onClick = onManualRun, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("手动预约下一天($tomorrowDayUpper)")
            }
        }
    }
}

@Composable
private fun CurrentSeatRuleCard(selectedSeat: SeatOption?) {
    OutlinedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("当前座位规则（实时）", style = MaterialTheme.typography.titleSmall)
            if (selectedSeat == null) {
                Text("请先选择座位加载规则", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("开放时间：${selectedSeat.openStart}-${selectedSeat.openEnd}", style = MaterialTheme.typography.bodySmall)
                Text("时间步进：${selectedSeat.intervalMinutes} 分钟", style = MaterialTheme.typography.bodySmall)
                Text("最短时长：${selectedSeat.minResvMinutes} 分钟", style = MaterialTheme.typography.bodySmall)
                Text("最长时长：${selectedSeat.maxResvMinutes} 分钟", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RoomDropdown(
    rooms: List<RoomOption>,
    selectedRoomId: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit
) {
    val selected = rooms.firstOrNull { it.roomId == selectedRoomId }
    Box {
        OutlinedTextField(
            value = selected?.pathName ?: "请选择Room",
            onValueChange = {},
            readOnly = true,
            label = { Text("Room") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onExpandedChange(true) }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
            Text("选择")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            rooms.forEach { room ->
                DropdownMenuItem(text = { Text(room.pathName) }, onClick = { onSelect(room.roomId) })
            }
        }
    }
}

@Composable
private fun SessionStatusCard(ready: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.width(10.dp).height(10.dp).background(
                    if (ready) Color(0xFF2E7D32) else Color(0xFFF57C00)
                )
            )
            Column {
                Text("会话状态", style = MaterialTheme.typography.titleSmall)
                Text(if (ready) "已获取登录会话，可直接请求接口" else "未获取会话，请先登录抓取", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SeatDropdown(
    seats: List<SeatOption>,
    selectedSeatDevId: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit
) {
    val selected = seats.firstOrNull { it.devId == selectedSeatDevId }
    Box {
        OutlinedTextField(
            value = selected?.seatCode ?: "请选择座位",
            onValueChange = {},
            readOnly = true,
            label = { Text("座位") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onExpandedChange(true) }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
            Text("选择")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            seats.forEach { seat ->
                DropdownMenuItem(text = { Text(seat.seatCode) }, onClick = { onSelect(seat.devId) })
            }
        }
    }
}

@Composable
private fun WeekDayCard(
    dayText: String,
    slots: List<TimeRangeConfig>,
    seatRule: SeatOption?,
    onAdd: () -> Unit,
    onUpdate: (String, String, String, Boolean) -> Unit,
    onRemove: (String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(dayText, style = MaterialTheme.typography.titleSmall)
            slots.forEach { slot ->
                WeekSlotRow(slot = slot, seatRule = seatRule, onAdd = onAdd, onUpdate = onUpdate, onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun WeekSlotRow(
    slot: TimeRangeConfig,
    seatRule: SeatOption?,
    onAdd: () -> Unit,
    onUpdate: (String, String, String, Boolean) -> Unit,
    onRemove: (String) -> Unit
) {
    var start by remember(slot.id, slot.start) { mutableStateOf(slot.start) }
    var end by remember(slot.id, slot.end) { mutableStateOf(slot.end) }
    var enabled by remember(slot.id, slot.enabled) { mutableStateOf(slot.enabled) }
    val startError = validateTimeInput(start)
    val endError = validateTimeInput(end)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                onUpdate(slot.id, start, end, enabled)
            })
            Text("启用", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(2.dp))
                    Text("新建")
                }
                Button(onClick = { onRemove(slot.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(2.dp))
                    Text("删除")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = start,
                onValueChange = {
                    start = sanitizeTimeInput(it)
                    onUpdate(slot.id, start, end, enabled)
                },
                label = { Text("开始(HH:mm)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                isError = startError != null,
                supportingText = { startError?.let { Text(it) } }
            )
            OutlinedTextField(
                value = end,
                onValueChange = {
                    end = sanitizeTimeInput(it)
                    onUpdate(slot.id, start, end, enabled)
                },
                label = { Text("结束(HH:mm)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                isError = endError != null,
                supportingText = { endError?.let { Text(it) } }
            )
        }
        HorizontalDivider()
    }
}

private fun sanitizeTimeInput(raw: String): String {
    val filtered = raw.filter { it.isDigit() || it == ':' || it == '：' }.take(5)
    return if ((filtered.count { it == ':' } + filtered.count { it == '：' }) <= 1) filtered else filtered.replace(":", "").replace("：", "")
}

private fun validateTimeInput(value: String): String? {
    if (value.isBlank()) return null
    if (value.contains('：')) return "请使用英文冒号 :，不要使用中文："
    if (value.any { !it.isDigit() && it != ':' }) return "仅支持数字和英文冒号 :"
    if (value.count { it == ':' } > 1) return "时间格式错误，正确示例：07:15"
    if (!value.contains(':')) return "时间格式错误，正确示例：07:15"
    if (value.length != 5) return "请补全为 HH:mm 格式"
    val matcher = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    if (!matcher.matches(value)) return "时间无效，请输入 00:00-23:59"
    return null
}

@Composable
private fun MonitorTab(
    successMessages: List<String>,
    failMessages: List<String>,
    serviceEnabled: Boolean,
    serviceText: String,
    weekStates: Map<DayOfWeek, WeekServiceState>,
    dailyServiceState: TriggerServiceUiState
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ServiceMonitorCard(enabled = serviceEnabled, text = serviceText)
        ServiceTripleChart(title = "每日预约定时服务", state = dailyServiceState)
        WeekServiceChart(weekStates)
        DynamicPanel(title = "成功记录", items = successMessages, color = Color(0xFF1565C0))
        DynamicPanel(title = "失败记录", items = failMessages, color = Color(0xFFC62828))
    }
}

@Composable
private fun ServiceTripleChart(title: String, state: TriggerServiceUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ServiceStateCell("Alarm", state.alarmEnabled, Modifier.weight(1f))
                ServiceStateCell("Work", state.workEnabled, Modifier.weight(1f))
                ServiceStateCell("Job", state.jobEnabled, Modifier.weight(1f))
            }
            Text("计划触发时间：${state.nextTriggerText}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ServiceStateCell(label: String, enabled: Boolean, modifier: Modifier = Modifier) {
    val bg = if (enabled) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
    Box(
        modifier = modifier.height(36.dp).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White)
    }
}

@Composable
private fun ServiceMonitorCard(enabled: Boolean, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.width(12.dp).height(12.dp).background(
                    if (enabled) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                )
            )
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WeekServiceChart(states: Map<DayOfWeek, WeekServiceState>) {
    Card {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("每周状态", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                DayOfWeek.entries.forEach { day ->
                    val color = when (states[day] ?: WeekServiceState.DISABLED) {
                        WeekServiceState.ENABLED -> Color(0xFF2E7D32)
                        WeekServiceState.FAILED -> Color(0xFFD32F2F)
                        WeekServiceState.DISABLED -> Color(0xFF9E9E9E)
                    }
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp).background(color),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(day.name.take(3), color = Color.White)
                    }
                }
            }
            Text("灰=关闭  绿=正常  红=配置异常")
        }
    }
}

@Composable
private fun OccupyChart(blocks: List<OccupyBlock>) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(modifier = Modifier.fillMaxWidth(0.92f).height(56.dp)) {
                val fromTime = LocalTime.of(8, 0)
                val toTime = LocalTime.of(23, 0)
                val totalMinutes = Duration.between(fromTime, toTime).toMinutes().toFloat()
                drawRect(color = Color(0xFFEDE7F6), size = Size(size.width, size.height))
                blocks.forEach { block ->
                    val from = Duration.between(fromTime, block.start).toMinutes().coerceAtLeast(0).toFloat()
                    val to = Duration.between(fromTime, block.end).toMinutes().coerceAtMost(totalMinutes.toLong()).toFloat()
                    if (to > from) {
                        val x = size.width * (from / totalMinutes)
                        val w = size.width * ((to - from) / totalMinutes)
                        drawRect(color = Color(0xFF5E60CE), topLeft = Offset(x, 6f), size = Size(w, size.height - 12f))
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(0.92f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("08:00", "11:00", "14:00", "17:00", "20:00", "23:00").forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (blocks.isEmpty()) {
                Text("暂无占用数据")
            } else {
                Text(blocks.joinToString("；") { "${it.start}-${it.end}" })
            }
        }
    }
}

@Composable
private fun DynamicPanel(title: String, items: List<String>, color: Color) {
    val dynamicHeight = (80 + items.size * 36).dp
    Card(
        modifier = Modifier.fillMaxWidth().height(dynamicHeight),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = color)
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            if (items.isEmpty()) {
                Text("暂无记录")
            } else {
                LazyColumn { items(items) { msg -> Text("• $msg") } }
            }
        }
    }
}

@Composable
private fun LogsTab(
    logs: List<String>,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("运行日志")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExportLogs) {
                    Text("导出日志")
                }
                Button(onClick = onClearLogs) {
                    Text("清除日志")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxSize()) {
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无日志") }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    items(logs) { line ->
                        Text(line)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

private fun generateStartOptions(rule: SeatOption?): List<String> {
    if (rule == null) return emptyList()
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    val start = runCatching { LocalTime.parse(rule.openStart.take(5)) }.getOrDefault(LocalTime.of(8, 30))
    val end = runCatching { LocalTime.parse(rule.openEnd.take(5)) }.getOrDefault(LocalTime.of(22, 15))
    val interval = rule.intervalMinutes.coerceAtLeast(1)
    val latestStart = end.minusMinutes(rule.minResvMinutes.toLong())
    if (latestStart < start) return listOf(start.format(formatter))
    val result = mutableListOf<String>()
    var cursor = start
    while (!cursor.isAfter(latestStart)) {
        result += cursor.format(formatter)
        cursor = cursor.plusMinutes(interval.toLong())
    }
    return result
}

private fun generateEndOptions(rule: SeatOption?, startText: String): List<String> {
    if (rule == null) return emptyList()
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    val start = runCatching { LocalTime.parse(startText.take(5)) }.getOrNull() ?: return emptyList()
    val openEnd = runCatching { LocalTime.parse(rule.openEnd.take(5)) }.getOrDefault(LocalTime.of(22, 15))
    val minEnd = start.plusMinutes(rule.minResvMinutes.toLong())
    val maxByRule = start.plusMinutes(rule.maxResvMinutes.toLong())
    val maxEnd = if (maxByRule.isBefore(openEnd)) maxByRule else openEnd
    if (maxEnd < minEnd) return emptyList()
    val interval = rule.intervalMinutes.coerceAtLeast(1)
    val result = mutableListOf<String>()
    var cursor = minEnd
    while (!cursor.isAfter(maxEnd)) {
        result += cursor.format(formatter)
        cursor = cursor.plusMinutes(interval.toLong())
    }
    return result
}

