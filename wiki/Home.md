# GZHU Seat Booking Wiki

欢迎使用GZHU Seat Booking。

## 页面目录

- [使用教程](Usage-Guide.md)
- [接口信息](API-Reference.md)
- [应用架构](Architecture.md)
- [运维发布](Operations.md)

## 项目重要信息

- 应用名称：GZHU Seat Booking
- Android 包名：`com.gzhu.seatbooking.app`
- 当前版本：`1.3.1`
- 调度通道：`AlarmManager + WorkManager + JobScheduler`
- 自动重试参数：`30 轮 * 2 秒`

## v1.3.0 重点

- 配置页右上角改为 `‼️教程‼️` 按钮，提供完整操作引导
- 定时任务被系统提前拉起时，会等待到计划触发时间再执行
- DNS 解析失败（Unable to resolve host）自动每 5s 重试，最长 5min
- 请求频繁后最终成功的时段，不进入失败记录区但保留完整日志

## 页面预览

![APP首页](images/APP首页.jpg)

![监测页面](images/监测页面示意图.jpg)

![日志页面](images/日志页面图.jpg)

详细图文步骤请查看 [使用教程](Usage-Guide.md)。
