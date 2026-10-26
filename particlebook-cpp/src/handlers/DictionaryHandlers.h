#pragma once
// ── PB-010-B：本地词典桥接（dictionary:*）────────────────────────────
// DictionaryService 由 App 持有（缓存落在 %APPDATA%/particle-book/data/dictionary），
// 这里只做桥接：
//   dictionary:import   → 选文件（.csv = ECDICT / .ifo = StarDict）→ JobService 异步
//                         导入 → 写缓存；进度走 dictionary:progress 事件
//   dictionary:lookup   → 精确/前缀/词形还原（同步，微秒级）
//   dictionary:status   → 是否已加载 / 来源 / 词条数
//   dictionary:clear    → 清空内存词库并删除磁盘缓存
// 只读本地文件、不联网。
#include <string>

class BridgeServer;
namespace pb { class DictionaryService; class JobService; }

void RegisterDictionaryHandlers(BridgeServer* bridge,
                                pb::DictionaryService* dictionary,
                                pb::JobService* jobs,
                                const std::string& cachePath);
