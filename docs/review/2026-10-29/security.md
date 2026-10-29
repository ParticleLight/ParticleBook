# 安全与信任边界 review（2026-10-29）

> 审阅对象：`F:\VScode\Read` @ `1feb175`（工作区干净，未改动任何仓库文件）。
> 方式：**纯静态代码审查**（按任务要求未构建、未启动应用、未跑测试）。所有行号对应该提交。
> 范围：注入脚本（zlib_toolbar.js / download_card.js）、BridgeServer（来源校验 + 外部白名单 + raw 消息）、
> SecretStore（DPAPI）、TranslationService（Key / URL / 脱敏）、ZLibraryService（会话与证书、镜像）、
> WebViewHostWebView（证书放行、虚拟主机、脚本注入）、更新检查/下载、renderer fileReader 与 bridge 文件读写。

## 概要（最该先修的三件事）

1. **镜像名单的信任链没有终点**（SEC-01）：线路列表来自第三方导航页 `https://zz.ggonav.com/` 的 HTML，
   只做「http(s) + 根路径 + 非 CDN」过滤，**不校验 host 是否与 Z-Library 有关**；随后按「页面里有没有
   z-lib 字样」排序并自动导航，而会话期间 WebView2 对**任意主机**的证书错误一律放行 → 导航页被投毒
   或被劫持即可把用户送到伪登录页，账号密码直接失守。
2. **任意被加载的页面都能操作会话**（SEC-02）：`zlibSwitchTo` / `zlibClose` / `zlibLogout` 这三种
   raw 消息只挡 `Unknown` 来源，外部页面（镜像、被镜像嵌入的第三方站点、MITM 页面）可以直接把自己
   「钉」成当前线路（本次运行内持久）、强制退出登录、关闭 Z-Library 会话。
3. **事件通道不分来源**（SEC-03）：`EmitEvent` 永远投给「当前文档」，而 Z-Library 会话期间当前文档就是
   **不可信外部页面**；`app:openFiles`（本地绝对路径）、`book:importProgress`、`zlib:downloadComplete`
   的 `path` 都会广播给镜像页。

其余为 P2：更新包哈希由渲染层决定是否校验（SEC-04）、`_pb_files` 与 app 页面同源且虚拟主机为 ALLOW
（SEC-05）、`file:read` 无路径白名单（SEC-06）、证书放行窗口过宽且无提示（SEC-07）、生产构建开 DevTools
（SEC-08）、翻译 base URL 允许 http（SEC-09）、被拒调用不回包导致 promise 泄漏（SEC-10）。
**未发现可远程直接触发崩溃/数据丢失的 P0**；下述问题都不是「一行代码写错」，而是信任边界的口径问题。

## 发现

| ID | 严重度 | 位置(file:line) | 问题 | 影响 | 建议修法 |
|---|---|---|---|---|---|
| SEC-01 | P1 | `services/ZLibraryMirror.cpp:321-322`、`:87-97`、`:139-147`；`src/WebViewHostWebView.cpp:76-87` | 线路名单取自第三方导航页 HTML，无 host 白名单；任何根地址都能成为「线路」，按内容含 z-lib 字样排序；会话期证书错误全放行 | 导航页被投毒/劫持 → 用户被自动送到伪 Z-Library 登录页，凭据泄露（且自签证书被静默接受） | 名单改为内置签名清单或以 `z-lib/zlibrary/1lib` 等 host 白名单兜底；登录页域名与内置清单不一致时拒绝放行证书错误并显式提示 |
| SEC-02 | P1 | `src/BridgeServer.cpp:98-130`；`services/ZLibraryMirror.cpp:397-415` | `zlibNavigate/zlibClose/zlibLogout/zlibSwitchTo` 只挡 Unknown 来源，任何 http(s) 页面都能发 | 镜像页可把自己钉成当前线路（`m_mirrorPinned`，本次运行内持久）、清 Cookie 强制登出、关闭会话 | raw 类型也走来源 allowlist：只接受 `zlib:getMirrorInfo/switchMirror/navigate` 所需的最小动作；logout 需用户手势（例如 UI 上确认），不允许页面直接发 |
| SEC-03 | P1 | `src/BridgeServer.cpp:143-156`；`src/App.cpp:366` | `EmitEvent` 不判断当前文档来源，事件永远投给当前页面（含外部镜像页） | 本地绝对路径（`app:openFiles`）、导入进度文件名、下载落地路径泄露给不可信页面 | 事件加来源门：非 app 文档只投 `zlib:*` 下载/线路相关白名单事件；`app:openFiles` 等改走「仅 App 文档」通道 |
| SEC-04 | P2 | `handlers/FileHandlers.cpp:331-346`、`:468-482`；`handlers/FileHandlersUpdate.cpp:187-194` | 下载更新时 sha512 由渲染层传入；空串或 `dummy` 直接跳过校验 | 渲染层被 XSS/篡改即可让更新包「无哈希校验」落地执行（URL 仍锁 GitHub，故不是直接 RCE） | 由 C++ 缓存最近一次 `CheckUpdateImpl` 的 sha512 并与 URL 绑定；渲染层只触发、不提供期望值；无哈希一律拒绝安装 |
| SEC-05 | P2 | `src/WebViewHostWebView.cpp:94-103`；`handlers/FileHandlers.cpp:66-121` | 虚拟主机映射整个 `renderer/`（含 `_pb_files/` 书本副本）且用 `COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_ALLOW` | 任意 origin（镜像页）都能 GET app 源下的资源；书名副本 URL 只由 FNV-1a 路径哈希保护 | `_pb_files` 移出虚拟主机根目录，改用一次性 token URL；映射改为 `DENY_CORS` |
| SEC-06 | P2 | `handlers/FileHandlers.cpp:197-210`、`:50-61` | `file:read` 接受任意绝对路径并复制进 app 源 | 一旦渲染层出现 XSS（DOMPurify 例外/未来新渲染器），即为任意文件读取 + `downloadUpdate`+`quitAndInstall` 的 RCE 组合 | 只允许读「库里登记过的路径」+ 少量白名单扩展名；其余拒绝并记日志 |
| SEC-07 | P2 | `services/ZLibraryService.cpp:34-38`、`:148-158`；`src/WebViewHostWebView.cpp:76-87` | 证书放行只在 `Show()/Hide()` 之间，期间**任何主机**放行；用户离开 Z-Library 站点后依旧放行；无可见提示 | 会话期间中间人可以伪造任意 https 站点（含登录页），用户无从察觉 | 放行限定到「本次导航的 mirror 域及其跳转链」并在 UI 上显著提示不安全连接；离开该域即恢复校验 |
| SEC-08 | P2 | `src/WebViewHostWebView.cpp:59` | 生产构建 `put_AreDevToolsEnabled(TRUE)` | 任何能操作本机的人对 app 页面开 DevTools 即可调用全部桥接方法（读任意文件、装更新） | 用 `#ifdef _DEBUG` 或环境变量控制；Release 关闭 |
| SEC-09 | P2 | `services/TranslationService.cpp:76-80`、`:998-1001`、`:1111-1114` | base URL 允许 `http://`，Key 走 Authorization / x-api-key 头明文过网 | 公共网络下 Key 可被抓包（用户自填地址，属弱信任） | 仅允许 https（本地回环可例外）；http 时给出显式警告并记日志 |
| SEC-10 | P2 | `src/BridgeServer.cpp:86-91` | 被来源门拒绝的 invoke **不回包**（无 error response） | 外部页面每次调用都在自己的 `pending` Map 里留一条永不 settle 的 Promise；循环调用可让它自我 OOM（页面级 DoS），且失败不可诊断 | 拒绝时回 `{"type":"response","id":..,"error":"forbidden_origin"}`（或至少回一个空包） |

## 详细分析

### SEC-01 镜像名单来自第三方导航页，无域名白名单（P1）

触发路径：启动 2 秒后 `StartMirrorFetch` 后台抓 `https://zz.ggonav.com/` → 正则取 `<a href>` 与裸 URL →
`IsMirrorCandidate` 只要求 http(s)/根路径/非 CDN → 与内置 10 条 fallback 合并 → 并行探测 →
`SortMirrorsLocked` 按「是否 Z-Library 正式站 > 未知 > 只是提到 Z-Library > 其它 > 不通」+ 耗时排序 →
`SelectDefaultMirrorLocked` 直接导航第一条。整个过程没有任何「这个 host 应该属于 Z-Library」的判定。

```cpp
// services/ZLibraryMirror.cpp:321-322  名单来源（第三方导航页）
json ZLibraryService::FetchMirrors() {
    std::string html = FetchUrl("https://zz.ggonav.com/");

// :87-97  候选条件：只看 scheme/路径/是否 CDN，不看 host 语义
static bool IsMirrorCandidate(const std::string& rawUrl) {
    if (u.find('#') != std::string::npos || u.find('?') != std::string::npos) return false;
    if (path != "/" && !path.empty()) return false;
    if (host.find('.') == std::string::npos) return false;
    return !IsThirdPartyHost(host);      // host 只要不像 CDN 就通过

// :218-222  探测排序依据：正文里出现 "z-lib" 之类字样就算「提到 Z-Library」
if (LooksLikeZlibApp(head) || LooksLikeJsChallenge(head)) st.kind = ZMK_ZLIB_APP;
else if (LooksLikeZlibContent(head)) st.kind = ZMK_ZLIB_PAGE;
```

放大因素：会话期间 `m_allowUntrustedCerts` 让任意主机的证书错误被 `ALWAYS_ALLOW`（`WebViewHostWebView.cpp:81-83`），
所以即使伪镜像用自签证书也**不会**被拦；用户看到的是自己输入的 Z-Library 账号密码。
影响：核心功能（进站 + 登录）的信任根落在「一个第三方网站今天的 HTML 内容」上，属于供应链/钓鱼风险。
建议：镜像名单改为内置 + 可选的**签名**更新；无论如何对「登录页所在 host」做白名单（`z-lib*` / `zlibrary*` /
`1lib*` / `singlelogin*` 等），名单外的 host 一律不放行证书错误并在 UI 上提示。

### SEC-02 外部页面可直接操作会话（P1）

`HandleMessage` 对 `invoke` 做得很好（App/External/Unknown 三分 + 3 条白名单），但**另有一批 raw 类型**只
判断「不是 Unknown」：

```cpp
// src/BridgeServer.cpp:116-130
} else if (type == "zlibLogout") {
    if (origin == MsgOrigin::Unknown) return;
    auto it = m_methods.find("zlib:logout");
    if (it != m_methods.end()) { try { it->second(json::object()); } catch (...) {} }
} else if (type == "zlibSwitchTo") {
    if (origin == MsgOrigin::Unknown) return;
    auto it = m_methods.find("zlib:switchMirror");
    if (it != m_methods.end()) {
        int idx = msg.value("index", 0);
        json params = json::object(); params["index"] = idx;
        try { it->second(params); } catch (...) {}
    }
```

`zlib:switchMirror` → `SwitchMirror(index)` 会 `m_mirrorPinned = true; m_pinnedUrl = m_mirrors[index]`
（`ZLibraryMirror.cpp:402-408`），而 `SelectDefaultMirrorLocked` 优先返回被钉住的那条 —— 于是**页面自己**就能把
自己固定为后续每次「进站」的目标，直到进程退出。配合 `zlibLogout` → `DeleteAllCookies()`（`ZLibraryService.cpp:178-192`），
任何被加载的 http(s) 页面都能清掉用户登录态。
建议：raw 类型同样过 `IsAllowedForExternal` 口径；`logout` 从 raw 通道移除（工具栏按钮已有 UI，改走 C++ 侧确认），
`switchTo` 只接受「当前工具栏渲染的列表索引」且服务端再校验一次来源 host 是否与当前镜像同域。

### SEC-03 事件通道不分来源，本地路径会广播给外部页面（P1）

```cpp
// src/BridgeServer.cpp:143-156
void BridgeServer::EmitEvent(const std::string& event, const json& data) {
    json msg = {{"type","event"}, {"event",event}, {"data",data}};
    const std::string payload = msg.dump();
    m_webview->PostToUiThread([this, payload]() {
        if (m_webview) m_webview->PostMessageToRenderer(payload);   // 投给"当前文档"，无来源判断
    });
}

// src/App.cpp:366  —— 事件里是本地绝对路径
if (m_bridge) m_bridge->EmitEvent("app:openFiles", json(paths));
```

Z-Library 会话期间主 WebView 里加载的是镜像页（外部来源），而注入的 `download_card.js` 正是要它接收事件
（`download_card.js:63-65`）—— 机制相同，但**白名单没有区分**：用户在会话期间双击另一本书（单实例转发）时，
`app:openFiles` 连同 `C:\Users\…\某本书.epub` 一起进了镜像页；`zlib:downloadComplete` 还带 `path`
（`ZLibraryService.cpp:574-577`）。泄漏的是路径与文件名（不是文件内容），但足以暴露用户名/书单/下载目录结构。
建议：在 `EmitEvent` 增加一个「仅 App 文档」参数（或维护 App 文档存活状态），`zlib:*` 保持全投；
`PostMessageToRenderer` 改为接受目标文档标识（WebView2 支持 per-frame `postWebMessageAsJson` 的 target 选项）。

### SEC-04 更新包哈希由渲染层决定是否校验（P2）

```cpp
// handlers/FileHandlers.cpp:331-346   URL 只认官方前缀（这条做得对）
const char* kOfficialPrefix = "https://github.com/ParticleLight/ParticleBook/releases/download/";
if (downloadUrl.rfind(kOfficialPrefix, 0) != 0) { pb::LogWarn("update","rejected non-official url"); return json(nullptr); }

// :468-482  但 sha512 是渲染层传进来的；空 / "dummy" 直接跳过校验
if (!sha512.empty() && sha512 != "dummy") {
    std::string actual;
    if (!ComputeFileSha512(exePath, actual) || _stricmp(actual.c_str(), sha512.c_str()) != 0) { … }
}
```
调用方是注入脚本里的 `electronAPI.downloadUpdate(url, sha512)`（`BridgeServer.cpp:440`），值来自 `app:updateChecked`
事件（`FileHandlersUpdate.cpp:187-194`）。也就是说「装不装」的完整性判断被放在了**被审对象自己**（渲染层）手里：
一次 app 源 XSS 或一次手工 DevTools 调用就能以 `sha512=''` 让任意官方 URL 的包跳过校验。
建议：把期望 sha512 与下载 URL 一起在 C++ 侧缓存（`CheckUpdateImpl` 结果），`app:downloadUpdate` 只接受
「与缓存 URL 完全一致」的请求；无缓存哈希时拒绝下载而不是放行。

### SEC-05 `_pb_files` 与 app 同源，虚拟主机对外 ALLOW（P2）

```cpp
// src/WebViewHostWebView.cpp:100-102
wv3->SetVirtualHostNameToFolderMapping(L"particlebook.app", rendererPath.c_str(),
                                       COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_ALLOW);
// handlers/FileHandlers.cpp:66,117-121  书本副本就落在 renderer 子目录里，URL 在同一个 origin
auto rendererDir = (… / "renderer" / "_pb_files").wstring();
std::string url = "http://particlebook.app/_pb_files/" + fn + "?v=" + std::to_string(stamp.QuadPart);
```
ALLOW 的语义是「其它 origin 也能访问该 host 的资源」。也就是说镜像页可以对
`http://particlebook.app/_pb_files/<hash>` 发 `fetch`；保护只剩 64bit FNV-1a 路径哈希（不可枚举，但没有 token 鉴权）。
同时「书副本与 app 页面同源」意味着任何让 app 源执行脚本的缺陷都会直接读到全书内容。
建议：`_pb_files` 移出虚拟主机目录；映射改 `DENY_CORS`；需要时用一次性随机文件名 + 校验 query token。

### SEC-06 `file:read` 无路径白名单（P2）

```cpp
// handlers/FileHandlers.cpp:197-210
bridge->RegisterAsyncMethod("file:read", [cache, jobs](const json& p, …) {
    const std::string path = p.value("path", "");
    if (path.empty() || !jobs) { reject("invalid_params"); return; }
    … ReadFileForRenderer(cache, path) …      // 任意绝对路径 → 复制进 _pb_files 并回 URL
```
配合 SEC-04/SEC-08，这条「读任意文件 + 装更新包」的能力全部依赖「app 源里只有我们自己的 JS」这一假设；
而 app 源同时承载**消毒后的电子书 HTML**（`HtmlRenderer.tsx:84-101` DOMPurify）——消毒是正确的，但它成了唯一的
一道墙。建议：`file:read` 只接受数据库里登记过的 `file_path`（或 `dialog:openFile` 刚返回的路径），并在 C++ 侧
按扩展名白名单过滤。

### SEC-07 证书放行窗口比需要的宽（P2）

```cpp
// src/WebViewHostWebView.cpp:81-85
if (m_allowUntrustedCerts) {
    args->put_Action(COREWEBVIEW2_SERVER_CERTIFICATE_ERROR_ACTION_ALWAYS_ALLOW);
}
// 非会话期间不设 Action → 交给 WebView2 默认处理（不放行）
```
开关在 `ZLibraryService::Show()` 里置 true、`Hide()` 置 false（`ZLibraryService.cpp:34-38,148-158`）。
问题：① 粒度是「会话」而不是「会话内的镜像域及其跳转链」，期间用户通过站内链接走到**任何** https 站点都放行；
② 用户切走（例如点工具栏 back 回到应用页面后又进站）不会自动关闭；③ UI 上没有任何「不安全连接已放行」提示。
这是 `facts.md` 记录的产品取舍，但建议至少把放行收敛到「本次导航链上的 host」，并在界面上给一个常驻提示。
（另外 `m_allowUntrustedCerts` 是普通 `bool`，UI 线程写、事件回调读；实际都在 UI 线程，未观察到问题，见存疑。）

### SEC-08 生产构建打开 DevTools（P2）

```cpp
// src/WebViewHostWebView.cpp:58-61
m_webview->get_Settings(&m_settings);
m_settings->put_AreDevToolsEnabled(TRUE);
m_settings->put_IsScriptEnabled(TRUE);
```
Release 包同样打开。对 app 页面按 F12 就能在控制台里 `await window.electronAPI.readFile('C:/…')`、调
`downloadUpdate(url,'')+quitAndInstall()`。威胁模型是「本地同一用户」，不是提权，但把「桥接全部能力」暴露成一个
可交互后门，也让 SEC-04/06 的纵深防御失效。建议 `#ifdef _DEBUG`（或 `PB_DEVTOOLS=1` 环境变量）控制。

### SEC-09 翻译 base URL 允许 http（P2）

```cpp
// services/TranslationService.cpp:76-80
bool IsHttpUrl(const std::string& url) {
    return StartsWith(lower, "http://") || StartsWith(lower, "https://");
}
// :998-1006  只拒绝「URL 里带凭据/query/fragment」，不拒绝明文
if (!IsHttpUrl(trimmedBaseUrl)) { … }
if (UrlCarriesSecrets(trimmedBaseUrl)) { … }
```
Key 走 `Authorization: Bearer` / `x-api-key` 头（`:1025-1030`），明文 http 下会裸奔；好在重定向被显式禁止
（`:836-839`，避免头被带去别的主机）、日志也做了 `(set)` 与 `***` 脱敏。建议：只允许 `https://`（`http://127.0.0.1`
/`localhost` 例外），设置页对 http 给出警告。

### SEC-10 被拒 invoke 不回包（P2）

```cpp
// src/BridgeServer.cpp:86-91
if (origin == MsgOrigin::Unknown) return;
if (origin == MsgOrigin::External && !IsAllowedForExternal(method)) return;
ProcessInvoke(id, method, params);
```
注入脚本的 `invoke()` 会 `pending.set(id, …)` 并等 response（`BridgeServer.cpp:297-305`）。被拒绝时 C++ 静默丢弃，
Promise 永不 settle 且 Map 条目永不回收：外部页面循环调用 `electronAPI.getBooks()` 就能让自己的渲染进程内存持续增长
（最终页面崩溃/卡顿），且真正的失败（禁止 vs 方法不存在）在页面侧无法区分。建议回一个
`{"type":"response","id":id,"error":"forbidden_origin"}`。

## 存疑 / 未覆盖

1. **未做动态验证**：按任务约束没有启动应用，SEC-01/SEC-02/SEC-03 的「实际可达性」是静态推断。
   验证方法：以 Z-Library 会话进入镜像页后在 DevTools 控制台执行
   `chrome.webview.postMessage(JSON.stringify({type:'zlibSwitchTo',index:0}))` 观察是否被钉住；
   并注册 `chrome.webview.addEventListener('message', e=>console.log(e.data))` 后由第二实例转发一本书，
   看是否收到 `app:openFiles`。
2. **`zz.ggonav.com` 被抓的内容本身**：本机未联网核验其当前 HTML；也没有验证它在真实网络下会推荐哪些 host。
   若它已被投毒，SEC-01 的严重度应提升到 P0（直接凭据失守）。
3. **`m_allowUntrustedCerts` 的线程模型**：`WebViewHost.h:119` 是普通 `bool`（setter 在 `:44`），`Show/Hide`（UI 线程）写，
   `ServerCertificateErrorDetected` 回调读。WebView2 的回调在 UI 线程派发，故现实中不构成数据竞争；未用工具验证。
4. **`mutool/mobitool` 命令行拼接**：`LibraryService.cpp:270-276`、`PdfService.cpp:141`、`SearchIndexService.cpp:1134/1221` 都用
   `"path"` 硬拼 `CreateProcessW` 命令行。书路径若含 `"` 可注入参数；当前两条「攻击者可控的入库路径」都净化了
   `"`（`FileHandlers.cpp:640-647`、`ZLibraryDownload.cpp:397-405`），所以只算潜在边界。建议统一改用
   `utils/win_cmd.h` 的转义。
5. **CSP 缺失**：app 页面没有 Content-Security-Policy，DOMPurify 是唯一 XSS 防线；未评估 `vite preview`/生产
   构建下注入内联脚本的可行性（未跑构建）。
6. **备份恢复**：`backup:restore` 接受渲染层给的任意路径并覆盖 `reader.json`（zip 内封面条目名有 `path_traversal` 校验，见 `BackupService.h:116`），未逐行审完整个恢复流程，未列入上表。
7. **`_pb_files` 的 FNV-1a 哈希能否被外部页面间接获知**（例如通过 `zlib:downloadComplete` 的 path 反推）未验证。

## 已核对且没问题的点

1. **桥接来源校验是 fail-closed 的**：`get_Source` 失败时 `source` 为空 → `ClassifyOrigin` 归 `Unknown` → 直接丢弃
   （`WebViewHostPage.cpp:54-63` + `BridgeServer.cpp:41-51,86-89`）；`particlebook.app.evil.com` 也被前缀带斜杠的比较挡住。
2. **EPUB 书内脚本不会执行**：epub.js 默认 `iframe.sandbox = "allow-same-origin"`，应用钩子也只加
   `allow-popups`，**没有 `allow-scripts`**，并在注释里写明原因（`EpubRenderer.tsx:206-216`、
   `node_modules/epubjs/lib/managers/views/iframe.js:97-100`）。
3. **注入脚本只跑在顶层 http(s) 页面**：`window.top !== window` + 协议检查 + `particlebook.app` 直接 return
   （`zlib_toolbar.js:6-10`、`download_card.js:3-5`），2026-10-29 的 `about:srcdoc` 事故已有护栏。
4. **HTML/MD/FB2/DOCX 全部过 DOMPurify**，markdown-it 关掉原始 HTML（`html: false`）
   （`HtmlRenderer.tsx:19,84-101`）；TXT 走 `escapeHtml`。这是 app 源里唯一渲染不可信内容的入口。
5. **凭据不进 settings / 日志 / 备份**：设置层主动剔除「看起来像密钥」的键
   （`SettingsService.cpp:197-212,1053-1067`），Key 只进 DPAPI（`SecretStore.cpp:55-103`，per-user、带 entropy、
   `CRYPTPROTECT_UI_FORBIDDEN`、写前不做明文回退），翻译日志只输出 `apiKey=(set)` 并对错误体做
   `RedactWithSecret`（`TranslationService.cpp:186-197,1243-1253`）；翻译请求禁止跟随重定向，避免 Authorization 头被带走。
6. **下载文件名净化 + 导航协议守卫**：`StartDownloadThread` 去掉分隔符与 `..`
   （`ZLibraryDownload.cpp:397-410`），Z-Library 会话期间 `NavigationStarting` 拦掉非 http(s)
   （`ZLibraryDownload.cpp:200-206`）；下载与更新都走 WinHTTP（默认校验证书）。

---

## 修复落地（2026-10-29 · task-13，已与 Lead 确认口径）

> 本节记录 SEC-01/02/03 + 3 条 P2 的落地方式与**刻意取舍**，供 release note / 后续 PR 引用。
> 改动文件：`BridgeServer.cpp`、`WebViewHostWebView.cpp`、`services/ZLibraryMirror.{h,cpp}`（`.h` 新增）、
> `services/ZLibraryService.cpp`、`services/TranslationService.cpp`。**`App.cpp` 未改**（SEC-03 在 BridgeServer 统一收口），
> `_pb_files` 独立虚拟主机按计划不做（要改 handlers 侧 URL 拼接，跨 scope）。

| ID | 状态 | 落地方式 |
|---|---|---|
| SEC-01 | 已修 | 新增 `pb::zlib::IsTrustedHost/HostFromUrl`（`ZLibraryMirror.h`）：导航页抓来的候选 host 必须过 Z-Library 域名族白名单，不合格**直接丢弃**（`ZLibraryMirror.cpp` 的 `IsMirrorCandidate`）。证书错误从「会话期任意主机 ALWAYS_ALLOW」改为「会话期 &&（域名族 ‖ 本次会话应用自己导航过的 host）」，其余显式 `CANCEL` + WARN（`WebViewHostWebView.cpp`）。会话放行表在 `Show()/Hide()` 清空，进站/换线/手动选线导航前登记（`ZLibraryService.cpp`、`ZLibraryMirror.cpp`）；页面无法把 host 塞进表（入口已过滤）。探测出的 `finalUrl`（常为中转域）仍可直达，改为 `IsNavigableHttpRoot` 形态检查 + 登记。 |
| SEC-02 | 已修 | `zlibNavigate / zlibClose / zlibLogout / zlibSwitchTo` 只接受「应用页」或「host 命中 zlib 域名族」的文档；外部页的 invoke 白名单同样要求可信 host。 |
| SEC-03 | 已修（**折中口径，Lead 已批准**） | 分两类：**AppOnlyEvents**（`app:openFiles` / `app:updateChecked` / `app:updateDownloaded` / `app:updateError` / `app:downloadProgress` / `book:importProgress` / `bookSource:downloadProgress`）**只投应用页**；其余事件仍投当前文档，但**投给外部页面时剥掉本地路径字段**（`path` / `filePath` / `file_path` / `dir` / `directory` / `folder` / `sourcePath` / `downloadPath`）。**`zlib:downloadComplete` 与 `zlib:importComplete` 没有被整条掐成 app-only** —— 镜像页里注入的下载卡片靠它们收尾，掐掉会永远停在 100%；对外的副本已无 `path`，卡片只用 `fileName`。 |
| SEC-05 | **已缓解**（纵深防御，非根治） | `WebViewHostWebView.cpp` 新增 `NavigationStarting` 守卫：**文档级**导航（含重定向，看 `get_IsRedirected`）到 `http://particlebook.app/_pb_files` 一律 `put_Cancel(TRUE)` 并记 WARN。`_pb_files` 只应作为子资源（`<img src>` / `fetch(_pb_url)`）被读；一旦当文档打开就会继承 app 源的全部桥接能力。**不复用独立虚拟主机**：会打断渲染层的 `fetch(_pb_url)`（CORS/相对路径），需改 handlers 侧 URL 拼接，本轮不做。**残余风险仍在**：子资源依旧同源可读（虚拟主机是 `HOST_RESOURCE_ACCESS_KIND_ALLOW`，书本副本 URL 只由 FNV-1a 路径哈希保护），见下。 |
| SEC-04/06/07 | 开放 | 04（更新包 sha512 由渲染层传入）、06（`file:read` 任意路径）、07（证书放行窗口）跨 scope / 需产品决策，本轮不动。 |
| SEC-08 | 已修 | Release 关 DevTools（`PB_DEVTOOLS=1` 可临时打开）。 |
| B-09（backend.md，UAF 隐患） | 已修 | `BridgeServer` 增加活体令牌 `std::shared_ptr<std::atomic<bool>> m_alive`（构造时创建、析构置 false）：`EmitEvent`/`SendResponse` 排队的 lambda 与 async `respond`/`reject` 改为捕获「令牌 + 当时的 `WebViewHost*`」，执行时先 `alive->load(acquire)` 再投递，令牌失效即丢弃。App::Shutdown 先 `StopDispatcher` 再 `reset` bridge 时，队列里的残留回调不再解引用已析构的 `this`。 |
| SEC-09 | 已修 | baseUrl 非 https 且非回环 → `NotConfigured`（错误文案明确）。回环三件套放行：`localhost` / `[::1]` / `127.0.0.0/8`，且**必须正好 4 段纯数字 IPv4 字面量**（`127.evil.com`、`127.0.0.1.evil` 不算，已用 12 组用例核对）。 |
| SEC-10 | 已修 | 被拒的 invoke（Unknown / 不可信 External / 白名单外）回 `{"type":"response","id":…,"error":"forbidden_origin"}`，渲染层 promise 不再永久挂起。 |

**残余风险 / 后续项**
1. 会话放行表只覆盖应用**自己导航**的地址；`ZLibraryDownload.cpp:168` 的 `NewWindowRequested` 跳转未登记（由 Lead 在集成阶段补 `AddSessionHostFromUrl`）。
2. 域名族白名单是**模式**判据：能挡住「导航页塞任意 host」，挡不住注册 `zlib-lookalike.com` 这类仿冒域。
3. 镜像 302 到未登记的中转域时，其自签证书会被 CANCEL，该线路失败并由上层换线（比「任意主机放行」安全，但可能少一条线路）。
4. **SEC-05 只挡了文档级导航**：`_pb_files` 与 app 页面仍同源，虚拟主机仍是 `ALLOW` —— 同源页面（若将来出现任何 XSS）依然能 `fetch` 到书本副本；彻底解决要把 `_pb_files` 挪到独立虚拟主机 + 一次性 token URL（要改 `handlers/FileHandlers.cpp` 的 `_pb_url` 拼接，跨 scope）。
5. B-09 是「令牌 + 检查后使用」模式，检查与使用之间仍有极窄的竞争窗口（`~BridgeServer` 与 UI 线程执行回调之间）；彻底消除需要 join 投递队列，成本远大于收益，本轮按此收口。

**复验方法**：`nmake` 全量 + `ctest` 18/18 + E2E（`zlib-real-download` 断言 `downloadComplete.path` 来自应用页 → 仍应通过；`translation-mock` 用 `http://127.0.0.1` → 回环放行仍应通过）；日志 `%LOCALAPPDATA%/ParticleBook/logs` 里 `[zlib] certificate error allowed/blocked` 可直接看到放行决策。
