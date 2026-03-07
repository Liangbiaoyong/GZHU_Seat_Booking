# 接口信息

接口实现位于：`android-app/app/src/main/java/com/gzhu/seatbooking/app/data/network/LibraryApi.kt`

## 会话接口

- `GET /ic-web/auth/userInfo`
用途：校验会话、获取 `accNo`

## 房间与座位接口

- `GET /ic-web/seatMenu`
用途：获取房间树

- `GET /ic-web/reserve?roomIds=<id>&resvDates=<yyyyMMdd>&sysKind=8`
用途：获取指定房间在指定日期的座位和占用信息

## 预约接口

- `POST /ic-web/reserve`
用途：提交座位预约

关键参数：

- `appAccNo`
- `resvBeginTime`
- `resvEndTime`
- `resvDev` (devId)
- `captcha`

## 限频与重试机制

- 限频关键词：`请求频繁`、`操作频繁`、`请稍后`、`too many`、`too frequent`、`rate`
- 业务重试参数：`30 轮 * 2 秒`
- 高频探测窗口：`5 秒`（200ms 轮询间隔）