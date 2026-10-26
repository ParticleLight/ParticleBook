#include "handlers/BookMetadataHandlers.h"

#include "BridgeServer.h"
#include "services/DatabaseService.h"

void RegisterBookMetadataHandlers(BridgeServer* bridge, DatabaseService* db)
{
    // 元数据写入：patch 只带要改的字段（书名/作者/语言/出版社/简介 +
    // tags / series_name / series_index / rating）；返回落库后的完整 books 行。
    bridge->RegisterMethod("book:updateMetadata", [db](const json& p) -> json {
        const int id = p.value("id", 0);
        if (id <= 0) return nullptr;
        const json patch = p.value("patch", json::object());
        if (!patch.is_object()) return nullptr;
        return db->UpdateBookMetadata(id, patch);
    });

    // 书库筛选用的去重标签 / 系列列表（按字典序）。
    bridge->RegisterMethod("library:getTags", [db](const json&) {
        return db->GetAllTags();
    });
    bridge->RegisterMethod("library:getSeries", [db](const json&) {
        return db->GetAllSeries();
    });
}
