# PreserveSeat Android App

图书馆座位自动预约 Android 客户端（Kotlin + Jetpack Compose）。

## 功能摘要

- 每日定时预约（默认 `07:16`，支持周计划多时段）
- 预约前 30 秒会话预检与自动会话刷新
- 三通道调度冗余：AlarmManager + WorkManager + JobScheduler
- 房间/座位拉取、座位占用查询、预约结果归档
- 自动重试机制（针对“请求频繁/操作频繁”等限流反馈）

## 环境要求

- Android Studio（推荐最新稳定版）
- JDK 17
- Android SDK 35

## 构建与运行

在 `android-app` 目录执行：

```powershell
./gradlew.bat assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

## 模块结构

- `app/src/main/java/com/preserveseat/app/ui`：界面与状态管理
- `app/src/main/java/com/preserveseat/app/domain`：预约流程与业务规则
- `app/src/main/java/com/preserveseat/app/data`：本地存储与网络访问
- `app/src/main/java/com/preserveseat/app/worker`：定时调度、前台服务、任务执行器

## 详细文档

- `docs/ARCHITECTURE.md`：APP 服务架构与执行链路
- `docs/API_REFERENCE.md`：接口、请求参数、响应语义
- `docs/AUTH_COMMUNICATION.md`：通信认证与会话生命周期
- `docs/OPERATIONS.md`：部署、打包、排障、发布流程

## 合规与安全

- 不要将账号、密码、token、cookie 或日志快照提交到 Git。
- 请遵守目标系统使用规范，避免过高频率请求。
