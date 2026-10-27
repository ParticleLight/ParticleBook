#pragma once
// ── PB-024-C：BookSourceService 跨 TU 共用的小工具 ────────────────────
// 原 BookSourceService.cpp（755 行 god file）按职责拆成：
//   BookSourceService.cpp —— 构造/线程池/handler 注册
//   BookSourceHttp.cpp    —— WinHTTP 抓取 + HTML 抽取 + URL 规范化
//   BookSourceSearch.cpp  —— 搜索 URL 构造 / 单源搜索 / 聚合搜索
//   BookSourceContent.cpp —— 书籍详情 / 目录 / 下载导入
// 日志 URL 截断（PB-016-B）三个 TU 都要用，放这里避免各抄一份。

#include <string>

inline std::string LogUrl(const std::string& url)
{
    return url.size() <= 200 ? url : url.substr(0, 200) + "...";
}
