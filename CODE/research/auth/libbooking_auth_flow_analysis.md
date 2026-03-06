# libbooking.gzhu.edu.cn 认证链路技术文档（最终版）

- 更新日期: 2026-03-05
- 适用目标: 无 WebView 纯 HTTP/HTTPS 登录接入、会话校验、预约接口调用
- 数据来源: HAR 抓包 + 纯 HTTP 脚本实测

## 1. 结论总览

1. 最终可用链路已验证成功，关键顺序必须为：
   `pre-userInfo(未登录) -> auth/address -> toLoginPage -> CAS login -> doAuth(dynamic) -> ic-web/auth/token -> userInfo(成功)`。
2. 业务接口会话不是只看单一 token，而是依赖 `token + cookie + 服务端会话状态`。
3. 业务 token 的稳定来源是 `GET /ic-web/auth/userInfo` 成功响应 `data.token`。
4. 早期失败根因是缺失 `auth/address` 前置步骤，导致 `auth/token` 无法被有效消费。

## 2. 认证系统角色

- `newcas.gzhu.edu.cn`
  作用: CAS 统一认证，负责凭据校验和 ticket 签发。
- `libbooking.gzhu.edu.cn/authcenter`
  作用: 消费 CAS ticket，交换业务侧认证上下文。
- `libbooking.gzhu.edu.cn/ic-web`
  作用: 建立并验证图书馆业务会话，承载预约相关 API。

## 3. 最终登录链路（已实测闭环）

1. `GET https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo`
   预期: 未登录态返回 `code=300`。
2. `GET https://libbooking.gzhu.edu.cn/ic-web/auth/address?...`
   预期: `code=0`，`data` 返回服务端签发的 `toLoginPage` 动态 URL。
3. `GET http://libbooking.gzhu.edu.cn/authcenter/toLoginPage?...`
   预期: 302 到 CAS 登录页（包含动态 service/doAuth）。
4. `GET https://newcas.gzhu.edu.cn/cas/login?service=...`
   操作: 解析隐藏字段 `lt/execution/_eventId`。
5. 计算 `rsa = strEnc(username + password + lt, '1', '2', '3')`。
6. `POST https://newcas.gzhu.edu.cn/cas/login?service=...`
   提交字段: `rsa, ul, pl, lt, execution, _eventId`。
7. 跟随重定向: `doAuth(dynamic)` -> `/ic-web//auth/token?...` -> `/`。
8. 会话预热: 访问 `/` 与 `/ic-web/Language/getLanList`。
9. `GET /ic-web/auth/userInfo`
   成功判定: `code=0`，且 `data.token` 非空。

## 4. 历史失败与修复

- 失败现象:
  - `auth/token` 返回 `200` 空响应或未形成可用会话。
  - 后续 `userInfo` 持续 `code=300`。
- 根因:
  - 绕过 `auth/address`，直接构造或复用旧 `toLoginPage/doAuth`，服务端上下文不完整。
- 修复:
  - 必须先执行 `pre-userInfo -> auth/address`，使用服务端当次签发入口后再走 CAS。

## 5. token/cookie 机制说明

1. `auth/token` 更偏向会话交换和跳转，不保证直接产出可用业务 token。
2. 业务 token 应优先从 `userInfo.data.token` 获取。
3. 实际鉴权依赖 `token + cookie` 共同有效。
4. HAR 中出现“cookie请求头数量为0”是导出可见性问题，不代表服务端不使用 cookie。

## 6. Android 侧落地规范

- 登录模块建议拆分:
  1. 入口发现 (`auth/address`)
  2. CAS 表单提交 (`lt/execution/rsa`)
  3. 重定向消费 (`doAuth -> auth/token -> /`)
  4. 会话校验 (`userInfo`)
- 日志要求:
  - 记录每跳 `status/from/to`，但对 `ticket/token/uniToken` 做脱敏。
  - 记录关键阶段耗时与失败点（字段缺失、重定向异常、userInfo 非0）。
- 校验要求:
  - 断言 `userinfo.code == 0`
  - 断言 `userinfo.data.token` 非空

## 7. 风险与边界

- CAS 页面字段变化可能导致表单解析失效，需要及时同步。
- 站点可能启用验证码/风控，此时自动登录成功率会下降。
- 若后端调整 `auth/address` 参数策略，需要更新入口发现逻辑。

## 8. 推荐运维检查项

1. 登录失败时优先检查 `auth/address` 是否返回动态入口。
2. 检查重定向链是否命中动态 `doAuth` 而非历史固定值。
3. 检查 `userInfo` 返回码与 `data.token` 是否同时满足。
4. 检查 cookie 中是否含 `ic-cookie/JSESSIONID/CASTGC`（以实际站点为准）。
