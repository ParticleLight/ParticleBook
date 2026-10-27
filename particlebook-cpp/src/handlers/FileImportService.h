#pragma once
// ── PB-024-B：从 FileHandlers.cpp 拆出的「书库导入」职责 ───────────────
// ImportBookPaths 对应原 ImportPaths：book:import 的同步/异步两条路径都调它。
// 只做搬移；进度 / errors 汇总 / DOCX 转换 / AZW3 探测等行为不变。

#include <string>
#include "nlohmann/json.hpp"

class BridgeServer;
class DatabaseService;
namespace pb { class JobContext; }

namespace fileimport {

nlohmann::json ImportBookPaths(DatabaseService* db, const nlohmann::json& paths,
                              pb::JobContext& job, BridgeServer* bridge);

} // namespace fileimport
