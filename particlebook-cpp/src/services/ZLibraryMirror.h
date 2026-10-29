#pragma once
// ── 镜像主机信任判据（2026-10-29 review · SEC-01）────────────────────────
// 三处必须用同一口径，避免各写一份后漂移：
//   1) ZLibraryMirror.cpp      从第三方导航页抓来的候选 host 先过这里再入名单；
//   2) WebViewHostWebView.cpp  证书错误只在可信 zlib 主机上放行，其余 Cancel；
//   3) BridgeServer.cpp        raw postMessage（zlibNavigate/Close/Logout/SwitchTo）
//                              与 zlib invoke 白名单的来源判断。
//
// 本头文件只依赖 <string>：三个 TU 都不需要额外 include（不含 Windows / WebView2），
// 避免为了一个主机名判断把 WebView2 头拖进 BridgeServer。

#include <string>

namespace pb {
namespace zlib {

// 从 URL 取小写主机名：去掉 scheme / userinfo / 端口 / 路径 / IPv6 方括号。
// 解析失败（空、无 scheme、无 host）返回空串。
std::string HostFromUrl(const std::string& url);

// 主机名（小写；带端口也能吃）是否属于可信的 Z-Library 域名族。
//
// 口径（逐段 label 匹配，任一段命中即可）：
//   * 前缀命中：z-lib / z-library / zlibrary / zlib / 1lib
//     —— 覆盖 zh.z-lib.gd、zh.z-library.sk、zlib.re、zh.zlib0.ru、zh.1lib.sk；
//   * 包含命中：singlelogin / bookfi / fbiwarning / dfj101 / zzz101
//     —— 覆盖 zh.singlelogin.rs、zh.101fbiwarning.ru、zh.dfj101.ru、zh.zzz101.ru。
//
// 这是【模式白名单】而不是加密白名单：它能挡住「导航页塞任意 host」（旧行为：
// 任何根地址都能当线路），挡不住攻击者注册 zlib-lookalike.com 这类仿冒域 ——
// 残余风险写在 docs/review/2026-10-29/security.md（SEC-01）。
bool IsTrustedHost(const std::string& host);

// ── 会话级临时放行主机（只给证书错误用）─────────────────────────────────
// 为什么要它：镜像会 302 到无法预先枚举的中转域（实测 msn101.ru 这类），证书
// 可能是自签的。只用 IsTrustedHost 会把这类线路整条打死。
//
// 安全边界：加入集合的地址【只来自应用自己的数据】——
//   * 镜像表里的线路（已经是 IsTrustedHost 的）与
//   * 后台探测跟到的重定向终点（ProbeMirrorOnce 的 finalUrl）；
// 页面提供/注入的地址在候选过滤（IsMirrorCandidate）里就被丢弃了，进不来。
// 会话开始/结束（ZLibraryService::Show/Hide）会清空，不跨会话累积。
void ClearSessionHosts();
void AddSessionHostFromUrl(const std::string& url);
bool IsSessionHost(const std::string& host);

} // namespace zlib
} // namespace pb
