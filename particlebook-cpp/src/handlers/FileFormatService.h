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

// PB-008-D：AZW3/KF8 → EPUB（调用同目录 mobitool.exe）。成功时把转出的 EPUB
// 复制到 outputEpubPath（覆盖已有文件），失败时 error 里是人类可读原因。
struct Azw3Conversion {
    bool ok = false;
    std::string error;
};
Azw3Conversion ConvertAzw3ToEpub(const std::string& filePath, const std::string& outputEpubPath);

std::string DetectFormat(const std::string& path);
std::string GetFileName(const std::string& path);
int64_t GetFileSizeSafe(const std::string& path);
void DebugLog(const char* msg);

} // namespace filefmt
