#pragma once
// ── PB-021（A）：Locator —— 统一的「书内位置」模型 ─────────────────────
//
// 背景（docs/ARCHITECTURE_REVIEW.md P0-5、docs/BACKLOG.md PB-021）：
//   书签 / 高亮 / 笔记 / 阅读进度各存各的位置：EPUB 拿 CFI，TXT 拿 pos-<offset>
//   加百分比，HTML 拿元素 id 加滚动像素，PDF 拿页码；PDF 的「页内矩形」和
//   文本字符区间则根本没有结构可放。结果是标注、搜索、TTS 跟读、导出各自再
//   发明一套定位。本文件把「书内位置」收敛成一个带 version / kind 的 Locator：
//
//     epub   { "kind":"epub",   "cfi":"epubcfi(/6/4[c01]!/4/2/1:0)" }
//     text   { "kind":"text",   "offset":1234, "length":56 }
//     html   { "kind":"html",   "href":"ch03.xhtml#sec2", "offset":17 }
//     pdf    { "kind":"pdf",    "page":12, "rect":{"x":0.10,"y":0.20,"w":0.30,"h":0.05} }
//     scroll { "kind":"scroll", "percent":42.5 }
//
//   任何 kind 都可以再带 quote / contextBefore / contextAfter（选区文本与前后
//   文），它们是「内容改版、CFI / 页码失效」后的重定位线索。本文件只负责结构、
//   校验与可信度分级；真正的重定位策略留在上层（ReaderAdapter / PB-014）。
//
// 字段语义（避免踩旧数据里的同名歧义）：
//   * page     1 起的 PDF 页码（整数），不是百分比；
//   * percent  0..100 的滚动百分比（与渲染层 progress 一致），不是页码；
//   * offset   UTF-8 文本的字符偏移（按 Unicode 码点计，不是字节）；
//   * length   文本区间的长度（码点数，0 / 缺省表示「只定位到点」）；
//   * rect     归一化到 [0,1] 的页内矩形（相对页面宽高，原点左上），
//              缩放 / 换页 / 换分辨率都不需要重算；
//   * href     HTML 章节相对路径（可带 fragment），或旧 HtmlRenderer 的元素 id；
//   * cfi      EPUB CFI 字符串，原样透传，本层不解析内部语法。
//
// 设计约束（与 platform/SettingsService.h 一致，便于单测直接链接）：
//   * 纯 std + nlohmann/json，不包含 <windows.h>，不依赖 Win32 / 数据库；
//   * 无全局可变状态，所有函数任意线程可并发调用；
//   * 解析永不抛异常：FromJson / Parse 用 LocatorResult 带回 issues；
//   * 未知 kind、version 比本机新、缺必备字段一律 Error，不做「猜一个」——
//     标注宁可迁移时报出来，也不要悄悄落到错误位置；
//   * 只有「位置」进 Locator；标注对象（bookmark / highlight / note）在
//     domain/Annotation.h。
//
// 旧数据兼容（PB-014 迁移用）：LocatorFromLegacyPosition / LocatorFromLegacyProgress
// 把现有 DB 行里的 cfi / pos-<offset> / page / scroll_position / rect 映射成
// Locator。旧字段的 page 语义按格式不同（PDF 是页码，TXT 是百分比），所以函数
// 需要 LegacyBookFormat；格式未知时按可判定性最高的解释走并给出 Warning。
//
// 典型用法：
//   pb::Locator loc = pb::MakeEpubLocator(cfiRange);
//   loc.quote = selectedText;
//   std::string text = pb::SerializeLocator(loc);
//   pb::LocatorResult r = pb::ParseLocator(text);
//   if (!r.ok) { /* r.issues 里是 field / code / message / severity */ }
//
//   // 旧行迁移：
//   auto issues = std::vector<pb::LocatorIssue>{};
//   pb::Locator legacy = pb::LocatorFromLegacyProgress(row, pb::LegacyBookFormat::Text, &issues);

#include "nlohmann/json.hpp"

#include <cstddef>
#include <string>
#include <vector>

namespace pb {

using json = nlohmann::json;

// 当前 Locator JSON 版本。写入永远写 version；读到更大的版本 → Error
// （不猜未来语义），读到缺省 version → 按 1 处理并给 Warning。
inline constexpr int kLocatorVersion = 1;

// ── 位置种类 ─────────────────────────────────────────────────────────
enum class LocatorKind {
    Unknown = 0,
    Epub,   // EPUB CFI
    Text,   // 纯文本字符偏移（TXT / MOBI 抽取后的文本）
    Html,   // HTML 章节 href + 可选字符偏移
    Pdf,    // PDF 页码 + 可选归一化矩形
    Scroll  // 滚动百分比（无语义锚点的兜底）
};

// "epub" / "text" / "html" / "pdf" / "scroll" / "unknown"（小写，稳定）。
const char* LocatorKindName(LocatorKind kind) noexcept;
// 大小写不敏感解析；失败返回 false 且不改 out。
bool ParseLocatorKind(const std::string& text, LocatorKind& out) noexcept;

// kind 的排序序号（epub < text < html < pdf < scroll < unknown），
// 用于 CompareLocator 的确定性排序。
int LocatorKindRank(LocatorKind kind) noexcept;

// ── 归一化矩形（页内，0..1）─────────────────────────────────────────
struct NormalizedRect {
    double x = 0.0;  // 左边界
    double y = 0.0;  // 上边界
    double w = 0.0;  // 宽
    double h = 0.0;  // 高

    bool IsEmpty() const noexcept { return w <= 0.0 || h <= 0.0; }
    bool operator==(const NormalizedRect& other) const noexcept;
    bool operator!=(const NormalizedRect& other) const noexcept { return !(*this == other); }
};

// 允许 1e-6 的浮点误差（JSON 往返 / 不同端算出来的同一个小数）。
bool NearlyEqual(double a, double b) noexcept;

// ── 问题报告 ─────────────────────────────────────────────────────────
enum class LocatorSeverity {
    Warning,  // 可以继续使用；值被修复 / 被忽略 / 需要关注
    Error     // 输入有不能原样保留的东西（未知 kind、缺必备字段、越界…）
};

struct LocatorIssue {
    std::string field;    // 出问题的 JSON 路径：kind / cfi / rect.w / root …
    std::string code;     // 机器可读：root|version|kind|cfi|href|offset|length|page|rect|percent|quote
    std::string message;  // 英文短说明，渲染层负责本地化
    LocatorSeverity severity = LocatorSeverity::Warning;
};

// 是 Error 的条目数（Warning 不算）。
std::size_t CountErrors(const std::vector<LocatorIssue>& issues) noexcept;

// ── Locator ──────────────────────────────────────────────────────────
struct Locator {
    int version = kLocatorVersion;       // 写入恒为 kLocatorVersion
    LocatorKind kind = LocatorKind::Unknown;

    std::string cfi;                     // kind = Epub
    std::string href;                    // kind = Html
    long long offset = -1;               // kind = Text / Html；-1 = 未设置
    long long length = -1;               // kind = Text；-1 / 0 = 只定位到点
    int page = -1;                       // kind = Pdf；1 起；-1 = 未设置
    bool hasRect = false;                // kind = Pdf；rect 是否有效
    NormalizedRect rect;                 // kind = Pdf；归一化页内矩形
    double percent = -1.0;               // kind = Scroll；0..100；-1 = 未设置

    std::string quote;                   // 选区文本（重定位线索，可空）
    std::string contextBefore;           // 选区前文（可空）
    std::string contextAfter;            // 选区后文（可空）

    // 没有任何锚点（未知 kind、空 cfi、负 offset、无页码…）。
    bool IsEmpty() const noexcept;
    // 是否带选区文本 / 上下文（重定位的最低成本线索）。
    bool HasQuote() const noexcept;
    // 去重 / map key：kind + 主锚点，不包含 quote / context。
    // 同一位置、不同 quote → 同一个 key；key 相同 ⇒ CompareLocator == 0
    //（double 的近似相等只在 CompareLocator 里按 1e-9 判定，key 用 12 位有效数字）。
    std::string AnchorKey() const;

    bool operator==(const Locator& other) const noexcept;
    bool operator!=(const Locator& other) const noexcept { return !(*this == other); }
};

// 解析 / 规范化结果：locator 是尽力填出来的值，ok=false 时不可信。
struct LocatorResult {
    Locator locator;
    std::vector<LocatorIssue> issues;
    bool ok = false;                    // 无 Error 且根对象 / kind 解析成功
    bool HasErrors() const noexcept;
};

// ── 构造（不校验；字段不合法时 Validate 会报）────────────────────────
Locator MakeEpubLocator(std::string cfi);
Locator MakeTextLocator(long long offset, long long length = -1);
Locator MakeHtmlLocator(std::string href, long long offset = -1);
Locator MakePdfLocator(int page, const NormalizedRect* rect = nullptr);
Locator MakeScrollLocator(double percent);

// ── 校验 ─────────────────────────────────────────────────────────────
// 严格体检：不改值，只报问题。每个 kind 的必备字段：
//   Epub → cfi 非空；Text → offset >= 0；Html → href 非空；
//   Pdf → page >= 1（rect 若 hasRect 必须 w/h > 0 且整体落在 [0,1]）；
//   Scroll → percent 在 [0,100]。
std::vector<LocatorIssue> ValidateLocator(const Locator& locator);
bool IsValidLocator(const Locator& locator);
// 修复可自动修复的东西并给出 Warning：version 归一、rect 越界丢弃、
// length < -1 归一为 -1、quote / context 去首尾空白。kind 不合法不猜。
LocatorResult NormalizeLocator(const Locator& locator);

// ── JSON ─────────────────────────────────────────────────────────────
// 稳定输出：nlohmann::json 对象按键排序，数值按最短表示；只有已设置的
// 可选字段才写出（空 quote / -1 offset 不写）。
json ToJson(const Locator& locator);
std::string SerializeLocator(const Locator& locator);
// 不抛异常；输入不是对象、kind 非法、缺必备字段都进 issues。
LocatorResult LocatorFromJson(const json& value);
LocatorResult ParseLocator(const std::string& text);

// ── 相等 / 排序 / 稳定性比较 ─────────────────────────────────────────
// 同一位置：kind 相同且 AnchorKey 相同（忽略 quote / context / length 以外的
// 展示性字段；length 属于主锚点）。
bool SamePosition(const Locator& a, const Locator& b);
// 全序（-1 / 0 / 1）：kind 序号 → 各 kind 主锚点字段 → AnchorKey 兜底。
// 0 当且仅当 SamePosition(a, b)。
int CompareLocator(const Locator& a, const Locator& b);

// 重定位可信度分级（只做结构判断，不做实际重定位）：
//   None   不合法 / 空 locator
//   Weak   scroll 百分比（内容一改就漂）
//   Medium CFI / 文本偏移 / href / PDF 页码这类结构化锚点
//   Strong 结构化锚点 + 选区文本（或 PDF 页码 + 归一化矩形）
enum class LocatorStability { None = 0, Weak = 1, Medium = 2, Strong = 3 };

const char* LocatorStabilityName(LocatorStability stability) noexcept;
LocatorStability GetStability(const Locator& locator);
bool IsStable(const Locator& locator);  // >= Medium
// -1 = a 比 b 弱，0 = 同级，1 = a 比 b 强。
int CompareStability(const Locator& a, const Locator& b);

// ── 旧数据兼容（PB-014 迁移用）──────────────────────────────────────
enum class LegacyBookFormat {
    Unknown = 0,
    Epub,
    Text,
    Html,
    Pdf
};

const char* LegacyBookFormatName(LegacyBookFormat format) noexcept;

// 旧 DB 行（bookmarks / highlights / notes / reading_progress 的公共部分）的
// 位置字段 → Locator。识别的键（camelCase 与数据库 snake_case 都收）：
//   cfi            优先：以 "pos-<n>" 开头的 → Text{offset=n}（TextRenderer 的旧约定）；
//                  其余非空 → Epub{cfi}，format=Html 时 → Html{href=cfi}
//   page           format=Pdf → Pdf{page}；format=Text/Html → Scroll{percent=page}
//                  （旧 TextRenderer 把百分比写进 page）；
//                  format=Unknown → 整数且 >=1 按 Pdf{page} 处理并给 Warning
//   scrollPosition / scroll_position
//                  无 cfi / page / offset 时：有 progress（0..100）就用它做
//                  Scroll{percent}；只有滚动像素没有百分比时按 0% 记录并 Warning
//   rect          对象 {x,y,w,h} 或数组 [x,y,w,h]，归一化页内矩形；越界 → Warning 丢弃
//   text          → quote（高亮的选区文本）
// 什么都认不出来时返回 IsEmpty 的 Unknown Locator（issues 里会有 Warning）。
Locator LocatorFromLegacyPosition(const json& row, LegacyBookFormat format,
                                  std::vector<LocatorIssue>* issues = nullptr);
// 阅读进度行专用：多认 progress（0..100）与 scroll_position；同样永不抛异常。
Locator LocatorFromLegacyProgress(const json& row, LegacyBookFormat format,
                                  std::vector<LocatorIssue>* issues = nullptr);

} // namespace pb
