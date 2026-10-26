#pragma once
// ── PB-021（A）：Annotation —— 统一的标注对象模型 ─────────────────────
//
// 背景（docs/ARCHITECTURE_REVIEW.md P0-5、docs/BACKLOG.md PB-021）：
//   书签 / 高亮 / 笔记在数据库里是三张独立的 JSON 表（bookmarks / highlights /
//   notes），字段各自可选：bookmark 有 cfi|page|progress|title，highlight 有
//   cfi|page|text|color，note 有 highlight_id|cfi|page|content。同一本书的
//   「标注」散在三条腿上，导出、TTS 跟读、搜索、PDF 标注都要分别处理。
//   本文件把它们统一成一个 Annotation：type 区分书签 / 高亮 / 笔记，位置统一
//   用 domain/Locator.h 的 Locator，其余是展示与生命周期字段。
//
// 字段：
//   id          数据库行 id（> 0 表示已入库；0 表示新对象，由 DB 分配）
//   bookId      所属书（> 0 必填）
//   type        bookmark | highlight | note
//   locator     书内位置（见 Locator.h；高亮的选区文本放在 locator.quote）
//   color       高亮颜色（CSS 颜色：#rgb / #rrggbb / #rrggbbaa / rgb() / 命名色）
//   title       书签标题（旧 bookmarks.title）
//   note        笔记正文 / 高亮附注 / 书签备注（旧 notes.content / highlights.note）
//   createdAt   ISO-8601 UTC（与 DatabaseService::NowISO 同格式：…T…:…:….000Z）
//   updatedAt   同上；未提供时等于 createdAt，早于 createdAt 时被规范为后者
//   tags        标签（去重、去空白、最多 kMaxTags 个）
//   relatedId   旧 notes.highlight_id（0 = 无关联）；PB-014 迁移期保留，
//               新代码不要用它表达「一条笔记属于某个高亮」以外的语义
//
// 不变式（ValidateAnnotation 检查，NormalizeAnnotation 尽量修）：
//   * type 合法、bookId > 0、locator 合法（不能是空 locator）；
//   * highlight 必须有合法颜色；note 必须有非空正文；
//   * color 非空时必须合法；时间戳非空时必须是 ISO-8601；tags 无空白项。
//   PDF 标注不单列 type：用 type=highlight/note + locator.kind=pdf（带归一化
//   rect）表达，避免再加一个「只在 PDF 里有意义」的标注类型。
//
// 设计约束（与 Locator.h 一致）：
//   * 纯 std + nlohmann/json，不含 <windows.h>，单测直接链接 Annotation.cpp；
//   * 无全局可变状态；解析 / 校验永不抛异常，问题走 AnnotationResult.issues；
//   * JSON 只写已设置的字段；version 永远写出（kAnnotationVersion）。
//
// 旧数据兼容（PB-014 迁移入口，绝不抛异常）：
//   * FromLegacyBookmark(row)  ← m_data["bookmarks"] 的一行
//   * FromLegacyHighlight(row) ← m_data["highlights"] 的一行
//   * FromLegacyNote(row[, relatedHighlight]) ← m_data["notes"] 的一行；
//     旧 note 可能只存 highlight_id 而没有位置，此时传入对应的 highlight，
//     locator 从它继承（并记一条 Warning），否则会报 locator 错误。
//   迁移结果统一过 NormalizeAnnotation（补时间戳、修颜色大小写、去重 tags），
//   同时带回 legacy 位置映射的 Warning，方便迁移脚本汇总。
//
// 典型用法：
//   pb::Annotation a = pb::MakeHighlight(bookId, loc, "#fbbf24");
//   a.locator.quote = selectedText;
//   std::string text = pb::SerializeAnnotation(a);
//   pb::AnnotationResult r = pb::ParseAnnotation(text);
//   if (!r.ok) { /* r.issues: field / code / message / severity */ }

#include "domain/Locator.h"

#include <cstddef>
#include <string>
#include <vector>

namespace pb {

inline constexpr int kAnnotationVersion = 1;
// 单条标注最多保留的标签数（Normalize 截断，Validate 只 Warning）。
inline constexpr std::size_t kMaxAnnotationTags = 32;
// 单个标签的最大长度（字节）。
inline constexpr std::size_t kMaxAnnotationTagLength = 64;

// ── 标注类型 ─────────────────────────────────────────────────────────
enum class AnnotationType {
    Unknown = 0,
    Bookmark,
    Highlight,
    Note
};

// "bookmark" / "highlight" / "note" / "unknown"。
const char* AnnotationTypeName(AnnotationType type) noexcept;
// 大小写不敏感解析；失败返回 false 且不改 out。
bool ParseAnnotationType(const std::string& text, AnnotationType& out) noexcept;
// 排序序号（bookmark < highlight < note < unknown）。
int AnnotationTypeRank(AnnotationType type) noexcept;

// ── 问题报告 ─────────────────────────────────────────────────────────
enum class AnnotationSeverity {
    Warning,  // 可继续使用；值被修复 / 被忽略 / 需要关注
    Error     // 输入有不能原样保留的东西（未知 type、空 locator、坏颜色…）
};

struct AnnotationIssue {
    std::string field;    // type / locator.cfi / color / createdAt / tags[2] / root …
    std::string code;     // 机器可读：type|book|locator|color|note|timestamp|tags|version|root|parse|legacy
    std::string message;  // 英文短说明，渲染层负责本地化
    AnnotationSeverity severity = AnnotationSeverity::Warning;
};

// ── Annotation ──────────────────────────────────────────────────────
struct Annotation;

// 全序（-1 / 0 / 1）：bookId → Locator 位置 → type → createdAt → id。
int CompareAnnotation(const Annotation& a, const Annotation& b);
// 同一位置：bookId + type + SamePosition(locator)。
bool SamePosition(const Annotation& a, const Annotation& b);

struct Annotation {
    int version = kAnnotationVersion;
    long long id = 0;          // 0 = 新对象（DB 分配 id）
    long long bookId = 0;
    AnnotationType type = AnnotationType::Unknown;
    Locator locator;
    std::string color;
    std::string title;
    std::string note;
    std::string createdAt;
    std::string updatedAt;
    std::vector<std::string> tags;
    long long relatedId = 0;   // 旧 notes.highlight_id；0 = 无

    bool IsEmpty() const noexcept { return type == AnnotationType::Unknown; }
    // 与另一个标注指向同一本书的同一位置、且类型相同（用于去重 / 取消重复书签）。
    bool SamePositionAs(const Annotation& other) const;

    bool operator==(const Annotation& other) const noexcept;
    bool operator!=(const Annotation& other) const noexcept { return !(*this == other); }
    bool operator<(const Annotation& other) const { return CompareAnnotation(*this, other) < 0; }
};

struct AnnotationResult {
    Annotation annotation;
    std::vector<AnnotationIssue> issues;
    bool ok = false;                    // 无 Error
    bool HasErrors() const noexcept;
};

std::size_t CountAnnotationErrors(const std::vector<AnnotationIssue>& issues) noexcept;

// ── 构造（不填时间戳；入库前一般会过 NormalizeAnnotation）─────────────
Annotation MakeBookmark(long long bookId, Locator locator, std::string title = std::string());
// text 会写进 locator.quote（高亮的选区文本是重定位线索）。
Annotation MakeHighlight(long long bookId, Locator locator, std::string color,
                         std::string text = std::string());
Annotation MakeNote(long long bookId, Locator locator, std::string note,
                    std::string title = std::string());

// ── 颜色 ─────────────────────────────────────────────────────────────
// 合法：空串之外，#rgb / #rgba / #rrggbb / #rrggbbaa、rgb()/rgba()/hsl()/hsla()、
// CSS 命名色（字母，<= 32 字符）。拒绝带 ; < > { } 等可注入 CSS 的字符。
bool IsValidAnnotationColor(const std::string& color);
// Trim；#RRGGBB → #rrggbb（渲染层与 DB 里的历史大小写统一）。
std::string NormalizeAnnotationColor(const std::string& color);

// ── 时间戳 ───────────────────────────────────────────────────────────
// "YYYY-MM-DDTHH:MM:SS[.fff][Z|±HH:MM]"；与 DatabaseService::NowISO 兼容。
bool IsIso8601Timestamp(const std::string& text);
// 当前 UTC 时间（同样格式，毫秒固定 .000）。失败返回空串。
std::string NowIso8601();

// ── 校验 / 规范化 ────────────────────────────────────────────────────
std::vector<AnnotationIssue> ValidateAnnotation(const Annotation& annotation);
bool IsValidAnnotation(const Annotation& annotation);
// 修可修的：version、颜色大小写、tag 去重 / 截断、时间戳补全与倒序、
// locator 规范化、relatedId 负数归零；修不动的（未知 type、空 locator、
// highlight 坏颜色、note 空正文）保留为 Error。
AnnotationResult NormalizeAnnotation(const Annotation& annotation);

// ── JSON ─────────────────────────────────────────────────────────────
json ToJson(const Annotation& annotation);
std::string SerializeAnnotation(const Annotation& annotation);
AnnotationResult AnnotationFromJson(const json& value);
AnnotationResult ParseAnnotation(const std::string& text);

// ── 旧数据兼容（PB-014 迁移）─────────────────────────────────────────
// 旧行字段（camelCase / snake_case 都收）：
//   bookmarks  : id, book_id, cfi, page, progress, title, note, created_at
//   highlights : id, book_id, cfi, page, text, color, note, created_at
//   notes      : id, book_id, highlight_id, cfi, page, content, created_at, updated_at
// 位置映射见 LocatorFromLegacyPosition；旧行的 page / cfi 语义由 format 决定
// （Unknown 时按可判定性走并给 Warning）。返回结果已经过 NormalizeAnnotation。
AnnotationResult FromLegacyBookmark(const json& row,
                                    LegacyBookFormat format = LegacyBookFormat::Unknown);
AnnotationResult FromLegacyHighlight(const json& row,
                                     LegacyBookFormat format = LegacyBookFormat::Unknown);
// relatedHighlight 仅在旧 note 只有 highlight_id、没有位置时使用：
// locator 从该高亮继承，relatedId 仍然保留。
AnnotationResult FromLegacyNote(const json& row,
                                LegacyBookFormat format = LegacyBookFormat::Unknown,
                                const Annotation* relatedHighlight = nullptr);

} // namespace pb
