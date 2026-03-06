import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
HAR_PATH = ROOT / "har" / "libbooking.sanitized.har"
OUT_MD = ROOT / "libbooking_auth_flow_analysis.generated.md"


def norm_headers(headers):
    out = {}
    for h in headers or []:
        name = (h.get("name") or "").strip().lower()
        if not name:
            continue
        out.setdefault(name, []).append(h.get("value", ""))
    return out


def redacted(value: str, keep=8):
    if value is None:
        return ""
    s = str(value)
    if len(s) <= keep:
        return s
    return s[:keep] + "...<redacted>"


@dataclass
class EntryView:
    idx: int
    method: str
    url: str
    host: str
    path: str
    status: int
    redirect: str
    req_headers: dict
    resp_headers: dict
    post_text: str


def main():
    data = json.loads(HAR_PATH.read_text(encoding="utf-8"))
    entries_raw = data.get("log", {}).get("entries", [])
    pages = data.get("log", {}).get("pages", [])

    entries = []
    for i, e in enumerate(entries_raw, start=1):
        req = e.get("request", {})
        resp = e.get("response", {})
        url = req.get("url", "")
        up = urlparse(url)
        post = req.get("postData", {}) or {}
        entries.append(
            EntryView(
                idx=i,
                method=req.get("method", ""),
                url=url,
                host=up.netloc,
                path=up.path,
                status=resp.get("status", 0),
                redirect=resp.get("redirectURL", "") or "",
                req_headers=norm_headers(req.get("headers", [])),
                resp_headers=norm_headers(resp.get("headers", [])),
                post_text=post.get("text", "") or "",
            )
        )

    host_count = Counter(e.host for e in entries)
    status_count = Counter(e.status for e in entries)

    # Endpoint summary
    endpoint = defaultdict(lambda: {"count": 0, "statuses": Counter()})
    for e in entries:
        k = (e.method, e.host, e.path)
        endpoint[k]["count"] += 1
        endpoint[k]["statuses"][e.status] += 1

    # auth related
    auth_re = re.compile(r"cas|auth|token|login|userinfo|reserve|seatmenu|doAuth", re.I)
    auth_entries = [e for e in entries if auth_re.search(e.url)]

    # document redirect chain
    doc_chain = []
    for e in entries:
        if e.path and e.status in {200, 301, 302, 303, 307, 308}:
            doc_chain.append(e)
        if len(doc_chain) >= 12:
            break

    # cookie/token related observations
    token_req = []
    cookie_req = []
    set_cookie_resp = []
    for e in entries:
        if "token" in e.req_headers:
            token_req.append((e.method, e.url, e.req_headers.get("token", [""])[0]))
        if "cookie" in e.req_headers:
            cookie_req.append((e.method, e.url, e.req_headers.get("cookie", [""])[0]))
        if "set-cookie" in e.resp_headers:
            vals = e.resp_headers.get("set-cookie", [])
            set_cookie_resp.append((e.status, e.url, vals))

    # First CAS POST details
    cas_post = None
    for e in entries:
        if e.method == "POST" and "newcas.gzhu.edu.cn/cas/login" in e.url:
            cas_post = e
            break

    cas_post_keys = []
    if cas_post and cas_post.post_text:
        try:
            q = parse_qs(cas_post.post_text, keep_blank_values=True)
            cas_post_keys = sorted(q.keys())
        except Exception:
            pass

    md = []
    md.append("# libbooking.gzhu.edu.cn 登录流程与接口分析（基于 HAR）")
    md.append("")
    md.append(f"- 生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    md.append(f"- HAR文件: `{HAR_PATH}`")
    md.append(f"- 页面数: {len(pages)}")
    md.append(f"- 请求条目数: {len(entries)}")
    md.append("")

    md.append("## 1. 主机与状态概览")
    md.append("")
    md.append("### 主机分布")
    for host, c in host_count.most_common():
        md.append(f"- `{host}`: {c}")
    md.append("")
    md.append("### HTTP状态分布")
    for st, c in sorted(status_count.items()):
        md.append(f"- `{st}`: {c}")
    md.append("")

    md.append("## 2. 登录到主页的关键跳转链")
    md.append("")
    md.append("以下按 HAR 中前序关键请求整理（已省略静态资源）：")
    for e in doc_chain:
        redir = f" -> `{e.redirect}`" if e.redirect else ""
        md.append(f"- [{e.idx}] `{e.method}` `{e.url}` => `{e.status}`{redir}")
    md.append("")
    md.append("从链路看，核心流程是：")
    md.append("1. 向 CAS 登录接口 POST 凭据。")
    md.append("2. CAS 返回 `ticket(ST-...)` 并重定向到 `http://libbooking.../authcenter/doAuth/...`。")
    md.append("3. 服务端将 `http` 升级到 `https`（307 临时跳转）。")
    md.append("4. `authcenter/doAuth` 成功后 302 跳到 `/ic-web//auth/token?uuid=...&uniToken=...&extInfo=...`。")
    md.append("5. `/ic-web//auth/token` 再 302 回 `https://libbooking.gzhu.edu.cn`，主页 200 加载完成。")
    md.append("")

    md.append("## 3. 认证相关接口与作用")
    md.append("")
    md.append("说明: 作用是基于 URL 与行为推断，便于后续无 WebView 实现。")
    md.append("")

    def endpoint_role(method, host, path):
        p = path.lower()
        if "newcas.gzhu.edu.cn" in host and "/cas/login" in p:
            return "CAS登录入口，提交凭据后签发服务票据 ticket"
        if "/authcenter/doauth/" in p:
            return "业务系统接收 CAS ticket，完成服务侧登录态交换"
        if "/ic-web//auth/token" in p or "/ic-web/auth/token" in p:
            return "将 uniToken/uuid 转换为馆系统可用会话（通常伴随Cookie落地）"
        if "/ic-web/auth/userinfo" in p:
            return "会话有效性校验与用户信息获取（token+cookie）"
        if "/ic-web/seatmenu" in p:
            return "座位树/房间列表"
        if "/ic-web/reserve" in p:
            return "预约与座位查询接口（GET查可用座位，POST提交预约）"
        return "静态资源或业务辅助接口"

    top_eps = sorted(endpoint.items(), key=lambda kv: kv[1]["count"], reverse=True)
    for (method, host, path), rec in top_eps:
        if not auth_re.search(f"{host}{path}"):
            continue
        sts = ", ".join(f"{k}:{v}" for k, v in sorted(rec["statuses"].items()))
        md.append(f"- `{method} https://{host}{path}`")
        md.append(f"  作用: {endpoint_role(method, host, path)}")
        md.append(f"  次数/状态: {rec['count']} 次, [{sts}]")
    md.append("")

    md.append("## 4. libbooking 关键接口全览（本次HAR可见）")
    md.append("")
    lib_eps = []
    for (method, host, path), rec in endpoint.items():
        if "libbooking.gzhu.edu.cn" not in host:
            continue
        if not (path == "/" or path.startswith("/ic-web") or path.startswith("/authcenter")):
            continue
        lib_eps.append((rec["count"], method, host, path, rec["statuses"]))
    for count, method, host, path, stc in sorted(lib_eps, key=lambda x: (-x[0], x[3])):
        role = endpoint_role(method, host, path)
        sts = ", ".join(f"{k}:{v}" for k, v in sorted(stc.items()))
        md.append(f"- `{count}x` `{method} https://{host}{path}`")
        md.append(f"  推断作用: {role}")
        md.append(f"  状态统计: [{sts}]")
    md.append("")

    md.append("## 5. Cookie / Token 构建机制（从 HAR 推断）")
    md.append("")
    md.append("### 4.1 服务端票据交换")
    md.append("- CAS `POST /cas/login` 成功后，浏览器先拿到 `ticket`。")
    md.append("- `ticket` 进入 `authcenter/doAuth`，随后得到 `uniToken + uuid + extInfo` 并进入 `/ic-web/auth/token`。")
    md.append("- `/ic-web/auth/token` 完成后重定向到主页，表明馆系统会话已建立。")
    md.append("")
    md.append("### 4.2 请求头与会话材料")
    md.append(f"- HAR中携带 `token` 请求头的请求数: {len(token_req)}")
    md.append(f"- HAR中携带 `cookie` 请求头的请求数: {len(cookie_req)}")
    md.append(f"- HAR中返回 `Set-Cookie` 的响应数: {len(set_cookie_resp)}")
    if token_req:
        sample = token_req[0]
        md.append(f"- token头样例: `{sample[0]} {sample[1]}` token=`{redacted(sample[2])}`")
    if cookie_req:
        sample = cookie_req[0]
        md.append(f"- cookie头样例: `{sample[0]} {sample[1]}` cookie=`{redacted(sample[2])}`")
    md.append("- 注意: Chrome 导出的 HAR 可能因隐私策略隐藏 Cookie 值或省略 Set-Cookie，0 不代表服务端绝对未发。")
    md.append("")

    md.append("## 6. 无 WebView 登录实现方案（可行路径）")
    md.append("")
    md.append("目标是程序化复现浏览器认证链，核心是完整处理CAS字段、重定向和Cookie Jar。")
    md.append("")
    md.append("1. `GET https://newcas.gzhu.edu.cn/cas/login?service=...` 获取登录页。")
    md.append("2. 解析登录页隐藏字段（如 `lt`, `execution`, `_eventId` 等，具体以当日页面为准）。")
    md.append("3. 带上隐藏字段 + 用户名密码，`POST /cas/login`。")
    md.append("4. 跟随302到 `authcenter/doAuth/...ticket=ST-...`，处理 `http->https` 升级。")
    md.append("5. 跟随到 `/ic-web/auth/token?uuid=...&uniToken=...&extInfo=...`，让服务端落馆系统会话。")
    md.append("6. 最终访问 `/ic-web/auth/userInfo` 验证会话可用（code=0视为成功）。")
    md.append("7. 持久化 Cookie Jar 与 token（若前端仍要求 `token` 头则同步保存）。")
    md.append("")
    md.append("必要能力:")
    md.append("- 自动重定向控制与手工跟随（建议可记录每跳URL/状态）")
    md.append("- 完整 Cookie Jar（域、Path、Secure、HttpOnly）")
    md.append("- HTML 表单字段解析")
    md.append("- 失败兜底: 票据过期、验证码/风控、同源策略变化")
    md.append("")

    md.append("## 7. 客户端-服务端完整认证流程（具体）")
    md.append("")
    md.append("### 阶段A: CAS认证")
    md.append("- Client -> CAS `/cas/login` 提交凭据与动态字段。")
    md.append("- CAS 校验通过后发服务票据 `ST-...` 并302重定向到 service。")
    md.append("")
    md.append("### 阶段B: 服务票据消费")
    md.append("- Client -> `authcenter/doAuth/...ticket=ST-...`。")
    md.append("- 服务端消费票据，生成业务侧授权材料（例如 `uniToken/uuid/extInfo`）。")
    md.append("")
    md.append("### 阶段C: 馆系统会话建立")
    md.append("- Client -> `/ic-web/auth/token?...`。")
    md.append("- 服务端建立 ic-web 会话，返回主页重定向；同时通过 Cookie/前端存储准备后续调用认证。")
    md.append("")
    md.append("### 阶段D: 接口鉴权")
    md.append("- Client 调用 `/ic-web/auth/userInfo`，携带 token/cookie。")
    md.append("- 返回 code=0 代表会话成功，可继续 seatMenu/reserve 等业务接口。")
    md.append("")

    md.append("## 8. CAS POST 表单字段观察")
    md.append("")
    if cas_post is None:
        md.append("- 未在HAR中定位到 CAS POST。")
    else:
        md.append(f"- CAS POST URL: `{cas_post.url}`")
        md.append(f"- 响应状态: `{cas_post.status}`")
        if cas_post_keys:
            md.append(f"- 表单字段({len(cas_post_keys)}): `{', '.join(cas_post_keys)}`")
        else:
            md.append("- HAR未提供可解析的表单文本（可能被截断或编码）。")
    md.append("")

    md.append("## 9. 对现有 Android 实现的启示")
    md.append("")
    md.append("- 你当前代码通过 WebView 自动登录抓 token/cookie，属于“浏览器态模拟”。")
    md.append("- 若要去掉 WebView，必须补齐 CAS 表单提交链和票据交换链，不是单一接口能替代。")
    md.append("- 建议先实现一个纯 OkHttp 登录原型（仅登录+userInfo），成功后再替换 App 主链。")
    md.append("")

    OUT_MD.write_text("\n".join(md), encoding="utf-8")
    print(f"Wrote: {OUT_MD}")
    print(f"Total entries: {len(entries)}")
    print(f"Auth-related entries: {len(auth_entries)}")


if __name__ == "__main__":
    main()
