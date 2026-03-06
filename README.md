# 广州大学图书馆订座脚本APP

广州大学图书馆座位自动预约项目（Android 主工程 + 辅助脚本）。

## 仓库结构

- `android-app/`：Android 客户端主工程（Kotlin + Compose）
- `CODE/`：辅助脚本与研究资产（抓包/流程验证/脱敏分析）

重点子目录：

- `CODE/research/auth/`：认证链路研究脚本、技术文档、脱敏 HAR

## 快速开始

### Android 构建

1. 进入目录：`android-app`
2. 构建 Debug APK：

```powershell
./gradlew.bat assembleDebug
```

产物默认位于：`android-app/app/build/outputs/apk/debug/app-debug.apk`

## 文档导航

- 总览与构建：`android-app/README.md`
- 架构设计：`android-app/docs/ARCHITECTURE.md`
- 接口细节：`android-app/docs/API_REFERENCE.md`
- 通讯与认证：`android-app/docs/AUTH_COMMUNICATION.md`
- 运维与发布：`android-app/docs/OPERATIONS.md`

