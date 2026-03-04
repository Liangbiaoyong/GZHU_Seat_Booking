# 接口参考（Library API）

本文档基于 `LibraryApi.kt` 与 `ReservationEngine.kt` 的当前实现。

## 1. 基础信息

- 基础域名：`https://libbooking.gzhu.edu.cn`
- 通用请求头：
  - `accept: application/json, text/plain, */*`
  - `lan: 1`
  - `token: <token>`
  - `cookie: <cookieHeader>`

## 2. 会话与用户接口

### 2.1 校验会话

- 方法：`GET`
- 路径：`/ic-web/auth/userInfo`
- 用途：验证 token/cookie 是否仍有效
- 成功判定：响应 JSON `code == 0`

### 2.2 查询用户信息

- 方法：`GET`
- 路径：`/ic-web/auth/userInfo`
- 用途：提取 `accNo` 作为预约主体账号
- 关键字段：`data.accNo`

## 3. 房间与座位接口

### 3.1 房间树查询

- 方法：`GET`
- 路径：`/ic-web/seatMenu`
- 用途：获取可预约房间及层级结构

### 3.2 指定房间座位查询

- 方法：`GET`
- 路径：`/ic-web/reserve`
- 关键查询参数：
  - `roomIds=<roomId>`
  - `resvDates=<yyyy-MM-dd>`
  - `sysKind=8`
- 用途：获取房间当日座位与占用信息

## 4. 预约接口

### 4.1 发起预约

- 方法：`POST`
- 路径：`/ic-web/reserve`
- Content-Type：`application/json;charset=UTF-8`

请求体关键字段（由 `ReservationEngine.reserve` 组装）：

- `sysKind`: `8`
- `appAccNo`: 用户学工号（优先来自 `/auth/userInfo`）
- `memberKind`: `1`
- `resvMember`: `[appAccNo]`
- `resvBeginTime`: `yyyy-MM-dd HH:mm:ss`
- `resvEndTime`: `yyyy-MM-dd HH:mm:ss`
- `testName`: 座位编码（如 `103-041`）
- `devId`: 座位设备 ID
- `captcha`: 可选

成功判定：

1. HTTP `200`
2. 且业务 `code == 0`

兼容成功判定（已存在预约）：

- message 包含“有预约”或“已预约”时，视为成功。

## 5. 失败与重试语义

当响应 message 命中以下关键词，将进入重试队列：

- `请求频繁`
- `操作频繁`
- `请稍后`
- `too many`
- `too frequent`
- `rate`

默认重试策略：

- 最多 `8` 轮
- 每轮间隔 `9s`

## 6. 字段映射补充

- 座位展示编码：`seatCode`（如 `103-041`）
- 预约提交主键：`seatDevId`（`devId`）
- 时间段来源：`weekSchedule[dayOfWeek]` 中启用项

## 7. 兼容性说明

- 接口属于目标系统私有契约，字段可能变更。
- 建议保留“响应原文 `raw` + code + message”日志，便于协议调整时快速定位。
