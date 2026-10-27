#pragma once
// ── PB-024-A：从 FileHandlers.cpp 拆出的「更新检查」职责 ──────────────
// SHA-512（安装包完整性）与 GitHub 更新检查（阻塞 WinHTTP，必须在后台线程）。
// 这里不注册桥接方法；注册仍在 FileHandlers.cpp 的 RegisterFileHandlers。

#include <string>
#include <windows.h>
#include "nlohmann/json.hpp"

bool ComputeFileSha512(const std::wstring& path, std::string& outHex);
nlohmann::json CheckUpdateImpl();
