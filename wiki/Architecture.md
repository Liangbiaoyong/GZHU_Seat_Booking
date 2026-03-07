# 应用架构

## 分层结构

- `ui`：Compose 页面与状态管理
- `domain`：预约编排、重试、规则校验
- `data`：配置持久化、日志、网络访问
- `worker`：定时调度与执行通道

## 关键组件

- `AppViewModel`：配置保存、状态刷新、调度更新
- `ReservationEngine`：预约执行与重试核心
- `Scheduler`：Alarm/Work/Job 三通道调度
- `ReserveTaskRunner`：统一执行入口与去重

## 调度可靠性

- 三通道并行注册：Alarm + Work + Job
- 任一通道缺失自动自愈重建
- 触发去重：同一 token 只执行一次
- 检测超时补偿：任务超过计划触发时间未执行时触发补偿