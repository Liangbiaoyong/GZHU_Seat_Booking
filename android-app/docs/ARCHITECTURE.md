# GZHU Seat Booking 架构说明

## 1. 总体分层

工程采用 `ui -> domain -> data` 分层，并配套 `worker` 调度层：

- `ui`
  - `MainScreen`：配置录入、状态展示、手动触发入口
  - `AppViewModel`：状态编排、调用业务层、落盘配置
- `domain`
  - `ReservationEngine`：核心预约编排（会话预检、参数组装、执行、重试）
  - `ScheduleValidator`：时间合法性校验
  - `ReservationResultPipeline`：结果汇总/持久化
  - `SeatMapper`：座位编码映射
- `data`
  - `ConfigStore`：DataStore 持久化应用配置
  - `LogRepository`：本地日志（按时间保留）
  - `ReservationResultRepository`：预约结果记录
  - `LibraryApi`：HTTP 请求与纯 HTTP/HTTPS 会话刷新
- `worker`
  - `Scheduler`：统一调度编排（Alarm + Work + Job + precheck）
  - `ReserveAlarmReceiver` / `ReserveJobService` / `AlarmDispatchWorker`：多通道触发入口
  - `ReserveForegroundService`：前台执行通道（提升进程存活）
  - `ReserveTaskRunner`：统一 action 解析与执行
  - `BootReceiver`：开机后恢复每日任务

## 2. 核心对象关系

应用启动于 `GzhuSeatBookingApp`，集中初始化：

- `ConfigStore`
- `LogRepository`
- `ReservationResultRepository`
- `ReservationEngine`

`ReservationEngine` 是业务核心，依赖 `ConfigStore + LogRepository + LibraryApi`。

## 3. 调度架构（高可达）

每日任务采用三通道并行注册，减少 ROM 限制或单通道失效导致的漏触发：

1. AlarmManager（精确定时）
2. WorkManager（系统调度兜底）
3. JobScheduler（持久化任务兜底）

### 3.0 计划时间闸门（v1.3.0）

无论 Alarm / Work / Job 哪个通道被系统提前拉起，真正的预约执行都会进入“计划时间闸门”：

- 若当前时刻 < 计划触发时间：等待到计划时刻再执行业务
- 等待期间记录进度日志：`0% / 25% / 50% / 75% / 100%`
- 到达计划时刻后再进行 token 去重，避免提前执行导致 5 秒高频探测提前耗尽

### 3.1 预检机制

在目标触发前 `1min` 调度 `ACTION_DAILY_PRECHECK`：

- 执行会话 warmup
- 若刷新成功且当前已晚于原触发时间，则按补偿规则判断是否执行
- 否则等待正式触发时刻
- 保持与计划时间闸门兼容：预检只做监测与重连，不提前执行正式预约

监测与调度具备自愈能力：

- 任一通道缺失（Alarm/Work/Job）会自动重建
- 去重命中分支也会执行通道健康检查与重建

### 3.2 去重机制

通过 `Scheduler.tryConsumeExecutionToken` 对同一 token 仅消费一次，防止多通道重复执行。

## 4. 执行链路

`Scheduler` 触发后统一进入 `ReserveTaskRunner.run(...)`：

- `ACTION_DAILY`
  - 计划时间闸门等待
  - 去重
  - 调 `ReservationEngine.runForTomorrowBatch`
  - 遇到 `Unable to resolve host` 进入 `5s` 一次、最长 `5min` 的网络恢复重试
  - 执行后重排下一次每日任务，并刷新三通道状态
- `ACTION_DAILY_PRECHECK`
  - 调 `ReservationEngine.warmupSession`
  - 按结果决定是否立即补偿执行 `ACTION_DAILY`

执行结果由 `ReservationResultPipeline.record(...)` 入库，并由 `ReserveNotifier` 发通知。

其中 `ReservationResultPipeline` 在 v1.3.0 新增了“限频瞬时失败折叠”：

- 同一日期+座位+时段如果后续成功，之前的“请求频繁/操作频繁”失败不会进入失败记录面板
- 但所有原始尝试仍完整写入日志，便于排障追踪

## 5. 关键配置模型

`AppConfig` 主要字段：

- 认证：`account`, `password`, `token`, `cookieHeader`
- 调度：`autoEnabled`, `triggerTime`（默认 `07:15`）
- 座位：`roomId`, `roomName`, `seatCode`, `seatDevId`
- 计划：`weekSchedule`（按周几配置多个时段）

## 6. 设计取舍

- **可靠性优先**：多触发通道 + token 去重
- **安全性优先**：每次执行前会话校验，不信任过期 token
- **可恢复性优先**：开机重建任务，失败记录日志并可重试


