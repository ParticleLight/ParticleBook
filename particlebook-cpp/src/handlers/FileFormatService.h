#pragma once
// ── PB-024-B：从 FileHandlers.cpp 拆出的「格式 / 小工具」职责 ─────────
// 格式识别、文件名/大小、MOBI→文本（mutool）、debug.log 写入。
// 用 namespace 而不是全局函数：LibraryService 也有一个全局 DetectFormat，
// 同名会 ODR 冲突（原实现是靠 static 规避的）。

#include <string>
#include <cstdint>
#include "nlohmann/json.hpp"

namespace filefmt {

std::string ConvertMobiToText(const std::string& filePath);
std::string DetectFormat(const std::string& path);
std::string GetFileName(const std::string& path);
int64_t GetFileSizeSafe(const std::string& path);
void DebugLog(const char* msg);

} // namespace filefmt
