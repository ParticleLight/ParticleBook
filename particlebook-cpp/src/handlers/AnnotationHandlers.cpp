// ── PB-021：annotations:list/add/update/delete 桥接 handler ──────────
// 分层：
//   * 本文件：参数校验 + domain/Annotation 规范化（type / locator / 颜色 /
//     时间戳），把「前端能传什么」收在这里；
//   * DatabaseService：id 分配（nextAnnotationId）、旧表双写、持久化。
// 返回给渲染层的是库里的行（含 id / createdAt / updatedAt / legacyId /
// legacyType），不是入参回显。

#include "handlers/AnnotationHandlers.h"

#include "BridgeServer.h"
#include "services/DatabaseService.h"
#include "domain/Annotation.h"

namespace {

// 新增路径：完整 annotation 对象 → 规范化后的行；非法返回 null。
json NormalizeIncomingAnnotation(const json& input)
{
    if (!input.is_object()) return nullptr;

    const pb::AnnotationResult parsed = pb::AnnotationFromJson(input);
    if (!parsed.ok) return nullptr;  // type / bookId / locator / 时间戳有硬错误

    const pb::AnnotationResult normalized = pb::NormalizeAnnotation(parsed.annotation);
    if (!normalized.ok) return nullptr;  // 例如 highlight 没颜色、note 没正文

    return pb::ToJson(normalized.annotation);
}

// 更新路径：patch 是部分字段，不能整体规范化；只校验「给了的字段」。
bool IsValidAnnotationPatch(const json& patch)
{
    if (!patch.is_object()) return false;

    if (patch.contains("bookId")) {
        if (!patch["bookId"].is_number_integer() || patch["bookId"].get<int>() <= 0) return false;
    }
    if (patch.contains("type")) {
        if (!patch["type"].is_string()) return false;
        pb::AnnotationType type = pb::AnnotationType::Unknown;
        if (!pb::ParseAnnotationType(patch["type"].get<std::string>(), type)) return false;
    }
    if (patch.contains("locator")) {
        const pb::LocatorResult locator = pb::LocatorFromJson(patch["locator"]);
        if (!locator.ok) return false;
    }
    if (patch.contains("color") && patch["color"].is_string()) {
        const std::string color = patch["color"].get<std::string>();
        if (!color.empty() && !pb::IsValidAnnotationColor(color)) return false;
    }
    if (patch.contains("title") && !patch["title"].is_string()) return false;
    if (patch.contains("note") && !patch["note"].is_string()) return false;
    if (patch.contains("relatedId") && !patch["relatedId"].is_number_integer()) return false;
    if (patch.contains("tags")) {
        if (!patch["tags"].is_array()) return false;
        for (const json& tag : patch["tags"]) {
            if (!tag.is_string()) return false;
        }
    }
    return true;
}

} // namespace

void RegisterAnnotationHandlers(BridgeServer* bridge, DatabaseService* db)
{
    // 统一标注列表；bookId 缺省 / <= 0 表示全库（书签侧栏也可以一次拉全）。
    bridge->RegisterMethod("annotations:list", [db](const json& p) {
        const int bookId = p.value("bookId", 0);
        return db->GetAnnotations(bookId);
    });

    bridge->RegisterMethod("annotations:add", [db](const json& p) -> json {
        const json annotation = NormalizeIncomingAnnotation(p.value("annotation", json::object()));
        if (annotation.is_null()) return nullptr;
        return db->AddAnnotation(annotation);
    });

    bridge->RegisterMethod("annotations:update", [db](const json& p) -> json {
        const int id = p.value("id", 0);
        if (id <= 0) return nullptr;
        const json patch = p.value("patch", json::object());
        if (!IsValidAnnotationPatch(patch)) return nullptr;

        // 部分 patch 没法单独规范化：先并到库里那一行，再整体过 domain 校验
        // （这样 note 类型仍要求非空正文、highlight 仍要求合法颜色）。
        const json current = db->GetAnnotation(id);
        if (current.is_null() || !current.is_object()) return nullptr;
        json merged = current;
        static const char* const kPatchable[] = {
            "bookId", "type", "locator", "color", "title", "note", "tags", "relatedId",
        };
        for (const char* key : kPatchable) {
            if (patch.contains(key)) merged[key] = patch[key];
        }
        const pb::AnnotationResult parsed = pb::AnnotationFromJson(merged);
        if (!parsed.ok) return nullptr;
        const pb::AnnotationResult normalized = pb::NormalizeAnnotation(parsed.annotation);
        if (!normalized.ok) return nullptr;
        return db->UpdateAnnotation(id, pb::ToJson(normalized.annotation));
    });

    bridge->RegisterMethod("annotations:delete", [db](const json& p) -> json {
        const int id = p.value("id", 0);
        if (id > 0) db->DeleteAnnotation(id);
        return json::object();
    });
}
