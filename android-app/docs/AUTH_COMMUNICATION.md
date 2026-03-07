# 通讯与认证机制

## 1. 通信模型

客户端采用两类通信手段：

1. **OkHttp 直连 API**：业务查询与预约提交
2. **纯 HTTP/HTTPS 登录链路**：在 token/cookie 失效时自动恢复会话（不依赖 WebView）

## 2. 会话凭据

核心凭据由 `SessionInfo` 表示：

- `token`
- `cookieHeader`

所有业务请求均显式携带这两个字段。

## 3. 会话生命周期

### 3.1 校验

每次关键流程前，调用 `/ic-web/auth/userInfo` 校验会话：

- 有效：直接执行业务
- 无效：进入自动登录刷新

### 3.2 自动刷新（账号密码）

`refreshSessionByAccountPassword` 逻辑：

- 进行多次纯 HTTP 登录尝试
- 先调用 `/ic-web/auth/address` 获取服务端签发 `toLoginPage`
- 进入 CAS 登录页并解析隐藏字段 `lt/execution/_eventId`
- 计算 `rsa = strEnc(username + password + lt, '1', '2', '3')` 并提交 `/cas/login`
- 手工跟随 `doAuth -> /ic-web//auth/token -> 首页` 重定向链
- 通过 `/ic-web/auth/userInfo` 读取 `data.token` 与校验会话
- 从 CookieJar 提取目标域 cookie，形成 `token + cookieHeader`

成功后回写 `ConfigStore`，后续请求使用新会话。

## 4. 调度与认证协同

为降低定时点失败概率，调度层在触发前 1 分钟执行预检：

- 若会话可用，等待正式触发
- 若会话刷新成功且已过触发点，按补偿规则执行预约

补偿与重建规则：

- 检测到任务明显超时未执行时触发补偿
- 调度通道任一缺失时自动重建三通道

## 5. 错误处理

- 网络/解析异常：记录日志并返回失败结果
- 会话刷新失败：终止本次预约并保留失败信息
- 触发重复：通过 token 去重直接忽略

## 6. 安全实践建议

- 不在日志中记录完整账号、密码、cookie 原文
- 不提交包含敏感字段的配置快照文件
- 使用 HTTPS 域名访问，避免明文链路
- 公开仓库发布前，确认清理所有本地调试输出

## 7. 已知边界

- 统一认证流程可能含验证码/风控策略，自动登录并非 100% 稳定。
- CAS 隐藏字段或 `des.js` 逻辑变更时，需要同步更新登录实现。
