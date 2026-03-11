# 运维与发布手册

## 1. 本地构建

在 `android-app` 目录执行：

```powershell
./gradlew.bat clean assembleDebug
```

默认产物：

- `app/build/outputs/apk/debug/app-debug.apk`

## 2. 规范命名产物

建议命名格式：

`GZHU_SeatBooking_<buildType>_v<versionName>_<versionCode>.apk`

示例：

`GZHU_SeatBooking_debug_v1.3.0_30.apk`

推荐输出目录：

- `android-app/app/build/outputs/apk/<buildType>/`

## 3. 发布检查清单

1. `.gitignore` 生效，确认未纳入日志/缓存/本地配置
2. `gradlew` 与 `gradle/wrapper/*` 完整存在
3. 文档齐全（架构、接口、认证、运维）
4. 构建成功且 APK 可安装
5. 关键业务验证：登录、拉取座位、执行预约、查看结果与日志

## 4. GitHub 发布流程（建议）

1. 初始化/检查仓库：

```powershell
git init
git add .
git commit -m "chore: standardize repository and docs"
```

1. 关联远程并推送：

```powershell
git remote add origin <your-repo-url>
git branch -M main
git push -u origin main
```

1. 创建 Release 并上传 APK（建议同时上传签名 release 与签名 debug）：

```powershell
gh release create v1.3.0 \
    android-app/app/build/outputs/apk/release/GZHU_SeatBooking_release_v1.3.0_30.apk \
    android-app/app/build/outputs/apk/debug/GZHU_SeatBooking_debug_v1.3.0_30.apk \
    --title "v1.3.0" \
    --notes "signed release + signed debug"
```

## 5. 常见故障

### 5.1 定时触发不稳定

- 检查系统电池优化是否限制应用后台
- 查看日志是否出现重复触发被去重或会话失效
- v1.3.0 起，提前拉起会走“重投递”模式，不在 Worker 内长时间 delay
- 如出现 `Job was cancelled`，新版本会记录 WARN 取消路径，不进入失败记录面板

### 5.2 会话刷新失败

- 账号密码是否有效
- 目标网站认证流程是否变化（登录表单字段变化）
- 网络是否可访问 `libbooking.gzhu.edu.cn`
- 若出现 `Unable to resolve host`，系统会自动每 5s 重试，最长 5min

### 5.3 预约返回“请求频繁”

- 系统会自动进入重试队列
- 当前默认重试参数：30 轮 * 2 秒
- 仍失败时应降低调用频率并错峰执行
- 若最终成功，该时段不会进入失败记录区，但日志会完整保留每次尝试

### 5.4 日志导出路径

- 默认导出到系统 `Download/GZHU_SeatBooking/`
- 若公共 Download 不可用，自动回退到应用外部可访问目录（`Android/data/.../files/Download` 或 Documents）
- 日志中会记录本次导出实际目录，便于定位文件

