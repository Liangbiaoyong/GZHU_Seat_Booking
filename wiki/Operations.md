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
2. 打 tag（例如 `v1.2.1`）
3. 发布 GitHub Release 并上传 debug/release APK

## 重点检查项

1. 会话获取是否成功
2. 每日调度三通道是否全绿
3. 自动预约结果是否入库
4. 日志导出功能是否可用
