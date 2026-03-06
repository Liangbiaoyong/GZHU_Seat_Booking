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

`GZHU_SeatBooking_debug_v1.1.2_22.apk`

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

2. 关联远程并推送：

```powershell
git remote add origin <your-repo-url>
git branch -M main
git push -u origin main
```

3. 创建 Release 并上传 APK（建议同时上传签名 release 与签名 debug）：

```powershell
gh release create v1.1.2 \
	android-app/app/build/outputs/apk/release/GZHU_SeatBooking_release_v1.1.2_22.apk \
	android-app/app/build/outputs/apk/debug/GZHU_SeatBooking_debug_v1.1.2_22.apk \
	--title "v1.1.2" \
	--notes "签名 release + 签名 debug"
```

## 5. 常见故障

### 5.1 定时触发不稳定

- 检查系统电池优化是否限制应用后台
- 查看日志是否出现重复触发被去重或会话失效

### 5.2 会话刷新失败

- 账号密码是否有效
- 目标网站认证流程是否变化（登录表单字段变化）
- 网络是否可访问 `libbooking.gzhu.edu.cn`

### 5.3 预约返回“请求频繁”

- 系统会自动进入重试队列
- 仍失败时应降低调用频率并错峰执行
