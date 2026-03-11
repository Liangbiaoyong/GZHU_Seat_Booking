# 运维与发布

## 本地构建

在 `android-app` 目录执行：

```powershell
./gradlew.bat clean assembleDebug assembleRelease
```

## APK 命名建议

`GZHU_SeatBooking_<buildType>_v<versionName>_<versionCode>.apk`

## 发布流程

1. 提交代码并推送 `master`
2. 打 tag（例如 `v1.3.0`）
3. 发布 GitHub Release 并上传 debug/release APK

## 重点检查项

1. 会话获取是否成功
2. 每日调度三通道是否全绿
3. 定时任务提前拉起时是否等待到计划时刻再执行
4. 自动预约结果是否入库（频繁后成功不进入失败区）
5. DNS 解析失败是否触发 5s*5min 自愈重试
6. 日志导出功能是否可用
7. `Job was cancelled` 是否仅记录 WARN 取消日志而不进入失败记录区
8. 日志导出默认路径是否在 `Download/GZHU_SeatBooking/`（不可用时是否正确回退）
