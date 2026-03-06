# 无 UI 登录任务技术文档（GZHU libbooking）

## 1. 文档目的
本文件描述 `CODE/research/auth/non_webview_login.py` 的技术设计、实现细节、运行验证结果与当前边界，用于工程交付、排障追溯与后续迭代。

## 2. 任务范围
- 目标：在不使用 WebView/Playwright 参与实际登录提交的前提下，完成 CAS -> libbooking 会话建立并通过 `userInfo` 校验。
- 输出：
  - 可执行脚本：`CODE/research/auth/non_webview_login.py`
  - 运行结果：`CODE/research/auth/output/non_webview_session.json`
- 不包含：验证码识别、图形交互、人机验证绕过。

## 3. 运行环境与依赖
- OS: Windows
- Python: 3.13 (venv)
- 依赖包：
  - `requests`
  - `beautifulsoup4`
- 外部运行时：
  - `node`（用于执行 CAS 官方 `des.js`，计算 `rsa`）

## 4. 认证链路模型
### 4.1 已实现链路
1. 调用 `ic-web/auth/address` 同构入口（等价链路）生成 `toLoginPage`。
2. 请求 `authcenter/toLoginPage` 获取动态 CAS 登录地址（包含动态 `doAuth` 路径）。
3. 访问 CAS 登录页，解析隐藏字段：`lt`、`execution`、`_eventId`。
4. 使用官方 `des.js` 计算：`rsa = strEnc(username + password + lt, '1','2','3')`。
5. 提交 `POST /cas/login`。
6. 手工跟随重定向：
   - `.../authcenter/doAuth/<dynamic>?ticket=...`
   - `.../ic-web//auth/token?uuid=...&uniToken=...&extInfo=...`
7. 采集候选 token（URL 参数、响应头、cookie、响应体）。
8. 会话预热：访问主页与语言接口。
9. 调用 `GET /ic-web/auth/userInfo` 做最终判定。

### 4.2 时序（当前）
- 已完整跑通纯 HTTP/HTTPS 登录链路，`userInfo.code=0`。
- 关键分叉点：
  - 正确链路（当前实现）：`pre-userInfo(300)` -> `auth/address` -> `toLoginPage` -> CAS -> `doAuth` -> `auth/token(302)` -> 首页 -> `userInfo(0)`。
  - 错误链路（历史实现）：绕过 `auth/address` 直接构造 `toLoginPage`，会导致 `auth/token(200空响应)`，后续 `userInfo(300)`。

## 5. 关键实现细节
### 5.1 动态入口发现
- 不再依赖 HAR 旧值 `doAuth/4edbd...`。
- 按 HAR 真实顺序先调用：
  - `GET /ic-web/auth/userInfo`（未登录态探测）
  - `GET /ic-web/auth/address?...`
- 从 `auth/address.data` 取服务端签发的 `toLoginPage`，再进入 CAS。该步骤会绑定后续 `auth/token` 可消费上下文。

### 5.2 表单参数构造
- 提交参数：`rsa, ul, pl, lt, execution, _eventId`。
- `ul/pl` 由用户名和密码长度动态计算。

### 5.3 重定向控制
- 手工处理 301/302/303/307/308。
- 每跳记录 `method/url/status/location`，用于回放与审计。

### 5.4 token 提取策略
- 提取顺序：
  1. URL 查询参数：`token/uniToken/access_token`
  2. 响应头：`token/x-auth-token/authorization`
  3. Cookie 名称包含 `token/auth`
  4. 响应体 JSON 与正则兜底

### 5.5 会话预热策略
- 在 `auth/token` 后执行：
  - `GET https://libbooking.gzhu.edu.cn/`
  - `GET https://libbooking.gzhu.edu.cn/ic-web/Language/getLanList`

### 5.6 Token 生成与提取（HAR 结论）
- HAR 显示首个 `token` 请求头并非来自 `auth/token` 响应头，而是来自：
  - `GET /ic-web/auth/userInfo` 成功响应体中的 `data.token`。
- 因此脚本最终导出 token 优先级为：
  1. `userinfo.data.token`（业务 token，32位hex）
  2. 兜底 `uniToken`（CAS交换令牌）

## 6. 数据与安全
- 敏感字段：账号、密码、`uniToken`、`CASTGC`。
- 当前输出 JSON 保留链路调试字段，建议生产化时启用脱敏：
  - token/uniToken 截断
  - 不落地明文密码
- 通信为 HTTPS + 部分历史 HTTP 跳转；脚本按站点现网行为处理。

## 7. 观测与日志
- `CODE/research/auth/output/non_webview_session.json` 包含：
  - `bootstrap_*`（入口生成）
  - `redirect_hops`（完整跳转）
  - `final_response_headers`
  - `warmup_traces`
  - `userinfo_attempts`
- 便于直接定位失败发生点（CAS、doAuth、auth/token、userInfo）。

## 8. 验证记录（2026-03-05）
使用脱敏测试账号进行多轮实测：
- CAS 认证成功：能拿到 `ticket` 与 `CASTGC`。
- 动态 `doAuth` 成功：不再出现旧 service 的“认证超时”。
- `auth/token` 返回 `302 -> https://libbooking.gzhu.edu.cn`，并落地 `ic-cookie/JSESSIONID`。
- `userInfo` 校验成功：`code=0`。
- 提取业务 token 成功：`token_source=userinfo_data_token`。

## 9. 根因分析（已闭环）
- 根因：早期实现缺失 HAR 中的前置步骤 `ic-web/auth/address`，导致服务端上下文不完整，`auth/token` 不可消费（200空响应）。
- 修复：严格按 HAR 顺序补齐 `pre-userInfo -> auth/address -> toLoginPage`，随后链路恢复为 `auth/token(302)` 并可获取有效会话。

## 10. 后续建议
1. 将当前脚本提炼为独立模块（登录、会话存储、校验、重试）。
2. 对 `token`、`CASTGC`、`cardId` 做输出脱敏（仅保留前后若干位）。
3. 增加重试与熔断策略：CAS超时、网络抖动、5xx 重试。
4. 增加回归测试：
  - 登录成功断言 `userinfo.code==0`
  - 断言 `userinfo.data.token` 为 32 位 hex

## 11. 运行命令
```powershell
c:/Users/26634/Desktop/Projects/GZHU_Seat_Booking/.venv/Scripts/python.exe CODE/research/auth/non_webview_login.py --username <账号> --password <密码> --output CODE/research/auth/output/non_webview_session.json
```
