// ── PB-021（A）：Locator + Annotation 单元测试 ──────────────────────
// 不依赖 Win32 / 数据库 / 渲染层：链接 Locator.cpp + Annotation.cpp 即可。
//
// 覆盖（对应 docs/BACKLOG.md PB-021 验收 1/2/3）：
//   * Locator：五种 kind 的 JSON round-trip、版本 / kind / 必备字段校验、
//     归一化（length 0、越界 rect、版本迁移）、稳定性分级与比较、
//     同一位置判定与全序、旧行位置映射（cfi / pos-N / page / rect /
//     scrollPosition / progress）
//   * Annotation：type / 颜色 / 空 locator / bookId / 时间戳 / tags 校验，
//     JSON round-trip、Normalize 修复（颜色大小写、tags 去重、时间戳补齐与
//     倒序纠正）、相等 / 排序 / 同一位置判定
//   * 旧 DB 行迁移：bookmarks / highlights / notes 三种行 → Annotation，
//     包括「note 只有 highlight_id、位置从关联高亮继承」的路径

#include "domain/Annotation.h"

#include "utils/test_assert.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

using nlohmann::json;

namespace {

// ── 断言辅助 ─────────────────────────────────────────────────────────

bool HasLocatorIssue(const std::vector<pb::LocatorIssue>& issues, const std::string& code)
{
    for (const pb::LocatorIssue& issue : issues) {
        if (issue.code == code) return true;
    }
    return false;
}

bool HasLocatorIssue(const std::vector<pb::LocatorIssue>& issues, const std::string& code,
                     const std::string& field)
{
    for (const pb::LocatorIssue& issue : issues) {
        if (issue.code == code && issue.field == field) return true;
    }
    return false;
}

bool HasAnnotationIssue(const std::vector<pb::AnnotationIssue>& issues, const std::string& code)
{
    for (const pb::AnnotationIssue& issue : issues) {
        if (issue.code == code) return true;
    }
    return false;
}

bool HasAnnotationIssue(const std::vector<pb::AnnotationIssue>& issues, const std::string& code,
                        const std::string& field)
{
    for (const pb::AnnotationIssue& issue : issues) {
        if (issue.code == code && issue.field == field) return true;
    }
    return false;
}

bool Near(double a, double b)
{
    return std::fabs(a - b) < 1e-9;
}

const char* kCreated = "2026-10-20T09:00:00.000Z";
const char* kUpdated = "2026-10-21T10:30:00.000Z";

// ── Locator：五种 kind 的 round-trip ─────────────────────────────────

void TestLocatorRoundTrip()
{
    // epub：CFI + 选区文本 + 前后文
    pb::Locator epub = pb::MakeEpubLocator("epubcfi(/6/4[c01]!/4/2/1:0)");
    epub.quote = "被高亮的句子";
    epub.contextBefore = "上一句。";
    epub.contextAfter = "下一句。";
    {
        const std::string text = pb::SerializeLocator(epub);
        const pb::LocatorResult parsed = pb::ParseLocator(text);
        CHECK_TRUE(parsed.ok);
        CHECK_TRUE(parsed.locator == epub);
        CHECK_EQ(pb::LocatorKindName(parsed.locator.kind), std::string("epub"));
        const json value = pb::ToJson(epub);
        CHECK_EQ(value.at("version").get<int>(), 1);
        CHECK_EQ(value.at("kind").get<std::string>(), std::string("epub"));
        CHECK_EQ(value.at("cfi").get<std::string>(), epub.cfi);
        // 不相关字段不写出
        CHECK_TRUE(!value.contains("page"));
        CHECK_TRUE(!value.contains("percent"));
        CHECK_EQ(pb::SerializeLocator(epub), text);  // 稳定输出
    }

    // text：offset + length
    pb::Locator textLoc = pb::MakeTextLocator(1234, 56);
    textLoc.quote = "区间文本";
    {
        const pb::LocatorResult parsed = pb::ParseLocator(pb::SerializeLocator(textLoc));
        CHECK_TRUE(parsed.ok);
        CHECK_TRUE(parsed.locator == textLoc);
        CHECK_EQ(parsed.locator.offset, 1234);
        CHECK_EQ(parsed.locator.length, 56);
        CHECK_EQ(pb::ToJson(textLoc).at("kind").get<std::string>(), std::string("text"));
    }

    // text：只定位到点（length 缺省）
    {
        pb::Locator point = pb::MakeTextLocator(7);
        const pb::LocatorResult parsed = pb::ParseLocator(pb::SerializeLocator(point));
        CHECK_TRUE(parsed.ok);
        CHECK_EQ(parsed.locator.offset, 7);
        CHECK_EQ(parsed.locator.length, -1);
        CHECK_TRUE(parsed.locator == point);
    }

    // html：href + offset
    pb::Locator html = pb::MakeHtmlLocator("ch03.xhtml#sec2", 17);
    {
        const pb::LocatorResult parsed = pb::ParseLocator(pb::SerializeLocator(html));
        CHECK_TRUE(parsed.ok);
        CHECK_TRUE(parsed.locator == html);
        CHECK_EQ(parsed.locator.href, std::string("ch03.xhtml#sec2"));
        CHECK_EQ(parsed.locator.offset, 17);
    }

    // pdf：page + 归一化 rect
    pb::NormalizedRect rect;
    rect.x = 0.1;
    rect.y = 0.2;
    rect.w = 0.3;
    rect.h = 0.05;
    const pb::Locator pdf = pb::MakePdfLocator(12, &rect);
    {
        const pb::LocatorResult parsed = pb::ParseLocator(pb::SerializeLocator(pdf));
        CHECK_TRUE(parsed.ok);
        CHECK_TRUE(parsed.locator == pdf);
        CHECK_EQ(parsed.locator.page, 12);
        CHECK_TRUE(parsed.locator.hasRect);
        CHECK_TRUE(Near(parsed.locator.rect.x, 0.1));
        CHECK_TRUE(Near(parsed.locator.rect.w, 0.3));
        const json value = pb::ToJson(pdf);
        CHECK_TRUE(value.contains("rect"));
        CHECK_EQ(value.at("rect").at("h").get<double>(), 0.05);
    }

    // scroll：percent
    const pb::Locator scroll = pb::MakeScrollLocator(42.5);
    {
        const pb::LocatorResult parsed = pb::ParseLocator(pb::SerializeLocator(scroll));
        CHECK_TRUE(parsed.ok);
        CHECK_TRUE(Near(parsed.locator.percent, 42.5));
        CHECK_TRUE(parsed.locator == scroll);
    }

    // 空 / 默认构造：ToJson 仍带 version + kind=unknown
    {
        const pb::Locator empty;
        CHECK_TRUE(empty.IsEmpty());
        const json value = pb::ToJson(empty);
        CHECK_EQ(value.at("kind").get<std::string>(), std::string("unknown"));
        const pb::LocatorResult parsed = pb::ParseLocator(pb::SerializeLocator(empty));
        CHECK_TRUE(!parsed.ok);
        CHECK_TRUE(HasLocatorIssue(parsed.issues, "kind"));
    }
}

// ── Locator：校验 / 归一化 / 稳定性 / 排序 ───────────────────────────

void TestLocatorValidationAndNormalize()
{
    // 非法 kind
    {
        pb::Locator locator;
        const std::vector<pb::LocatorIssue> issues = pb::ValidateLocator(locator);
        CHECK_TRUE(HasLocatorIssue(issues, "kind", "kind"));
        CHECK_TRUE(!pb::IsValidLocator(locator));
    }

    // epub：空 / 纯空白 cfi
    {
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(pb::MakeEpubLocator("")), "cfi", "cfi"));
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(pb::MakeEpubLocator("   ")), "cfi", "cfi"));
        CHECK_TRUE(pb::IsValidLocator(pb::MakeEpubLocator("epubcfi(/6/4)")));
    }

    // text：负 offset / 长度越界
    {
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(pb::MakeTextLocator(-1)), "offset", "offset"));
        pb::Locator locator = pb::MakeTextLocator(10);
        locator.length = -2;
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(locator), "length", "length"));
        CHECK_TRUE(pb::IsValidLocator(pb::MakeTextLocator(0, 5)));
    }

    // html：空 href；offset 可以为 -1（只定位章节）
    {
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(pb::MakeHtmlLocator("")), "href", "href"));
        CHECK_TRUE(pb::IsValidLocator(pb::MakeHtmlLocator("ch1.xhtml")));
    }

    // pdf：page 0 / rect 空 / rect 越界 / rect 落在 [0,1] 内
    {
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(pb::MakePdfLocator(0)), "page", "page"));
        pb::NormalizedRect bad;
        bad.x = 0.1;
        bad.y = 0.1;
        bad.w = 0.0;  // 空矩形
        bad.h = 0.1;
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(pb::MakePdfLocator(3, &bad)), "rect", "rect"));

        pb::NormalizedRect overflow;
        overflow.x = 0.8;
        overflow.y = 0.1;
        overflow.w = 0.5;  // x + w > 1
        overflow.h = 0.1;
        CHECK_TRUE(
            HasLocatorIssue(pb::ValidateLocator(pb::MakePdfLocator(3, &overflow)), "rect", "rect"));

        pb::NormalizedRect good;
        good.x = 0.2;
        good.y = 0.3;
        good.w = 0.4;
        good.h = 0.05;
        CHECK_TRUE(pb::IsValidLocator(pb::MakePdfLocator(3, &good)));
    }

    // scroll：percent 越界
    {
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(pb::MakeScrollLocator(-1.0)), "percent"));
        CHECK_TRUE(HasLocatorIssue(pb::ValidateLocator(pb::MakeScrollLocator(120.0)), "percent"));
        CHECK_TRUE(pb::IsValidLocator(pb::MakeScrollLocator(0.0)));
        CHECK_TRUE(pb::IsValidLocator(pb::MakeScrollLocator(100.0)));
    }

    // rect 挂在非 pdf 上：Warning 而不是 Error
    {
        pb::Locator locator = pb::MakeEpubLocator("epubcfi(/6/4)");
        locator.hasRect = true;
        locator.rect.w = 0.1;
        locator.rect.h = 0.1;
        const std::vector<pb::LocatorIssue> issues = pb::ValidateLocator(locator);
        CHECK_TRUE(HasLocatorIssue(issues, "rect"));
        CHECK_TRUE(!HasLocatorIssue(issues, "cfi"));
        CHECK_TRUE(pb::IsValidLocator(locator));  // Warning 不算 invalid
        const pb::LocatorResult normalized = pb::NormalizeLocator(locator);
        CHECK_TRUE(normalized.ok);
        CHECK_TRUE(!normalized.locator.hasRect);
    }

    // Normalize：length 0 → -1，给 Warning
    {
        pb::Locator locator = pb::MakeTextLocator(10, 0);
        const pb::LocatorResult normalized = pb::NormalizeLocator(locator);
        CHECK_TRUE(normalized.ok);
        CHECK_EQ(normalized.locator.length, -1);
        CHECK_TRUE(HasLocatorIssue(normalized.issues, "length", "length"));
    }

    // Normalize：越界 rect 丢弃但保留页码；版本 0 迁移到 1
    {
        pb::NormalizedRect overflow;
        overflow.x = 2.0;
        overflow.y = 0.0;
        overflow.w = 0.1;
        overflow.h = 0.1;
        pb::Locator locator = pb::MakePdfLocator(5, &overflow);
        locator.version = 0;
        const pb::LocatorResult normalized = pb::NormalizeLocator(locator);
        CHECK_TRUE(normalized.ok);
        CHECK_EQ(normalized.locator.page, 5);
        CHECK_TRUE(!normalized.locator.hasRect);
        CHECK_EQ(normalized.locator.version, pb::kLocatorVersion);
        CHECK_TRUE(HasLocatorIssue(normalized.issues, "rect"));
        CHECK_TRUE(HasLocatorIssue(normalized.issues, "version-migrated"));
    }

    // 稳定性分级
    {
        CHECK_TRUE(pb::GetStability(pb::Locator()) == pb::LocatorStability::None);
        CHECK_TRUE(pb::GetStability(pb::MakeScrollLocator(10.0)) == pb::LocatorStability::Weak);

        const pb::Locator epub = pb::MakeEpubLocator("epubcfi(/6/4)");
        CHECK_TRUE(pb::GetStability(epub) == pb::LocatorStability::Medium);
        CHECK_TRUE(pb::IsStable(epub));

        pb::Locator epubWithQuote = epub;
        epubWithQuote.quote = "选区";
        CHECK_TRUE(pb::GetStability(epubWithQuote) == pb::LocatorStability::Strong);

        pb::NormalizedRect rect;
        rect.w = 0.2;
        rect.h = 0.1;
        CHECK_TRUE(pb::GetStability(pb::MakePdfLocator(2, &rect)) == pb::LocatorStability::Strong);
        CHECK_TRUE(pb::GetStability(pb::MakePdfLocator(2)) == pb::LocatorStability::Medium);

        CHECK_EQ(pb::CompareStability(pb::MakeScrollLocator(1.0), epub), -1);
        CHECK_EQ(pb::CompareStability(epub, epubWithQuote), -1);
        CHECK_EQ(pb::CompareStability(epub, epub), 0);
        CHECK_TRUE(!pb::IsStable(pb::MakeScrollLocator(1.0)));
    }

    // 同一位置 / 全序
    {
        pb::Locator a = pb::MakeEpubLocator("epubcfi(/6/4)");
        pb::Locator b = a;
        b.quote = "不同正文";
        CHECK_TRUE(pb::SamePosition(a, b));
        CHECK_EQ(pb::CompareLocator(a, b), 0);
        CHECK_TRUE(a != b);
        CHECK_EQ(a.AnchorKey(), b.AnchorKey());

        pb::Locator c = pb::MakeEpubLocator("epubcfi(/6/6)");
        CHECK_TRUE(!pb::SamePosition(a, c));
        CHECK_EQ(pb::CompareLocator(a, c), -1);
        CHECK_EQ(pb::CompareLocator(c, a), 1);

        // 不同 kind 按 rank 排序：epub < text < html < pdf < scroll
        pb::Locator textLoc = pb::MakeTextLocator(0);
        pb::Locator html = pb::MakeHtmlLocator("h");
        pb::Locator pdf = pb::MakePdfLocator(1);
        pb::Locator scroll = pb::MakeScrollLocator(0.0);
        CHECK_EQ(pb::CompareLocator(a, textLoc), -1);
        CHECK_EQ(pb::CompareLocator(textLoc, html), -1);
        CHECK_EQ(pb::CompareLocator(html, pdf), -1);
        CHECK_EQ(pb::CompareLocator(pdf, scroll), -1);
        CHECK_EQ(pb::CompareLocator(scroll, pb::Locator()), -1);
    }
}

// ── Locator：JSON 错误输入 ───────────────────────────────────────────

void TestLocatorJsonErrors()
{
    auto parse = [](const char* text) { return pb::ParseLocator(text); };

    {
        const pb::LocatorResult r = parse("not json");
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "parse"));
    }
    {
        const pb::LocatorResult r = pb::LocatorFromJson(json::array());
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "root"));
    }
    {
        const pb::LocatorResult r = pb::LocatorFromJson(json{ { "version", 1 } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "kind"));
    }
    {
        const pb::LocatorResult r = pb::LocatorFromJson(json{ { "version", 1 }, { "kind", 5 } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "type"));
    }
    {
        const pb::LocatorResult r =
            pb::LocatorFromJson(json{ { "version", 1 }, { "kind", "epub-cfi" } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "kind"));
    }
    {
        // 未来版本：明确拒绝，不猜语义
        const pb::LocatorResult r = pb::LocatorFromJson(
            json{ { "version", 99 }, { "kind", "epub" }, { "cfi", "epubcfi(/6/4)" } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "version"));
    }
    {
        // 缺 version：Warning + 按 1 处理，仍然可用
        const pb::LocatorResult r =
            pb::LocatorFromJson(json{ { "kind", "epub" }, { "cfi", "epubcfi(/6/4)" } });
        CHECK_TRUE(r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "version-missing"));
        CHECK_EQ(r.locator.version, 1);
    }
    {
        // 空 cfi 就是空 locator
        const pb::LocatorResult r =
            pb::LocatorFromJson(json{ { "version", 1 }, { "kind", "epub" } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "cfi", "cfi"));
    }
    {
        // 类型不对：offset 写字符串
        const pb::LocatorResult r = pb::LocatorFromJson(
            json{ { "version", 1 }, { "kind", "text" }, { "offset", "12" } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasLocatorIssue(r.issues, "type", "offset"));
    }
    {
        // rect 数组形式 [x,y,w,h] 可接受
        const pb::LocatorResult r = pb::LocatorFromJson(
            json{ { "version", 1 }, { "kind", "pdf" }, { "page", 2 }, { "rect", { 0.1, 0.2, 0.3, 0.1 } } });
        CHECK_TRUE(r.ok);
        CHECK_TRUE(r.locator.hasRect);
        CHECK_TRUE(Near(r.locator.rect.y, 0.2));
    }
    {
        // 坏 rect：Warning 丢弃，页码仍可用
        const pb::LocatorResult r = pb::LocatorFromJson(
            json{ { "version", 1 }, { "kind", "pdf" }, { "page", 2 }, { "rect", { { "x", 0.1 } } } });
        CHECK_TRUE(r.ok);
        CHECK_TRUE(!r.locator.hasRect);
        CHECK_TRUE(HasLocatorIssue(r.issues, "rect"));
    }
    {
        // null 字段按缺省处理（旧 DB 行里常见）
        const pb::LocatorResult r = pb::LocatorFromJson(
            json{ { "version", 1 }, { "kind", "pdf" }, { "page", 3 }, { "rect", nullptr } });
        CHECK_TRUE(r.ok);
        CHECK_EQ(r.locator.page, 3);
    }
}

// ── Annotation：JSON round-trip / 校验 / Normalize ───────────────────

void TestAnnotationRoundTrip()
{
    pb::Locator locator = pb::MakeEpubLocator("epubcfi(/6/4[c01]!/4/2/1:0)");
    locator.quote = "被高亮的句子";
    locator.contextBefore = "上一句。";
    locator.contextAfter = "下一句。";

    pb::Annotation highlight = pb::MakeHighlight(3, locator, "#FBBF24", "被高亮的句子");
    highlight.note = "  我的批注  ";
    highlight.title = "高亮 1";
    highlight.tags = { " 重点 ", "读书", "重点", "" };
    highlight.createdAt = kCreated;
    highlight.updatedAt = kUpdated;

    const pb::AnnotationResult normalized = pb::NormalizeAnnotation(highlight);
    CHECK_TRUE(normalized.ok);
    CHECK_EQ(normalized.annotation.color, std::string("#fbbf24"));
    CHECK_EQ(normalized.annotation.note, std::string("我的批注"));  // Normalize 去首尾空白
    CHECK_EQ(static_cast<int>(normalized.annotation.tags.size()), 2);
    CHECK_EQ(normalized.annotation.tags[0], std::string("重点"));
    CHECK_EQ(normalized.annotation.tags[1], std::string("读书"));
    CHECK_TRUE(HasAnnotationIssue(normalized.issues, "tags"));

    const pb::Annotation& saved = normalized.annotation;
    const std::string text = pb::SerializeAnnotation(saved);
    const pb::AnnotationResult parsed = pb::ParseAnnotation(text);
    CHECK_TRUE(parsed.ok);
    CHECK_TRUE(parsed.annotation == saved);  // 全字段 round-trip
    CHECK_EQ(pb::AnnotationTypeName(parsed.annotation.type), std::string("highlight"));
    CHECK_EQ(parsed.annotation.locator.quote, std::string("被高亮的句子"));
    CHECK_EQ(parsed.annotation.createdAt, std::string(kCreated));

    const json value = pb::ToJson(saved);
    CHECK_EQ(value.at("version").get<int>(), 1);
    CHECK_EQ(value.at("type").get<std::string>(), std::string("highlight"));
    CHECK_EQ(value.at("locator").at("kind").get<std::string>(), std::string("epub"));

    // bookmark：可选字段为空时不写出
    {
        pb::Annotation bookmark = pb::MakeBookmark(
            3, pb::MakeScrollLocator(12.5), "书签 1");
        bookmark.createdAt = kCreated;
        bookmark.updatedAt = kCreated;
        const pb::AnnotationResult n = pb::NormalizeAnnotation(bookmark);
        CHECK_TRUE(n.ok);
        const json j = pb::ToJson(n.annotation);
        CHECK_TRUE(!j.contains("color"));
        CHECK_TRUE(!j.contains("tags"));
        CHECK_TRUE(!j.contains("relatedId"));
        const pb::AnnotationResult p = pb::ParseAnnotation(pb::SerializeAnnotation(n.annotation));
        CHECK_TRUE(p.ok);
        CHECK_TRUE(p.annotation == n.annotation);
    }

    // note：正文必填；PDF 标注用 highlight + pdf locator 表达
    {
        pb::NormalizedRect rect;
        rect.x = 0.1;
        rect.y = 0.2;
        rect.w = 0.3;
        rect.h = 0.04;
        pb::Annotation pdfNote =
            pb::MakeNote(4, pb::MakePdfLocator(9, &rect), "这段要再想想");
        pdfNote.color = "#60A5FA";
        pdfNote.relatedId = 7;
        const pb::AnnotationResult n = pb::NormalizeAnnotation(pdfNote);
        CHECK_TRUE(n.ok);
        const pb::AnnotationResult p = pb::ParseAnnotation(pb::SerializeAnnotation(n.annotation));
        CHECK_TRUE(p.ok);
        CHECK_TRUE(p.annotation == n.annotation);
        CHECK_TRUE(p.annotation.locator.hasRect);
        CHECK_EQ(p.annotation.relatedId, 7);
        CHECK_EQ(p.annotation.createdAt.empty(), false);
        CHECK_TRUE(pb::IsIso8601Timestamp(p.annotation.createdAt));
    }

    // 空 locator / 未知 type 的 JSON 必须报错
    {
        const pb::AnnotationResult r = pb::AnnotationFromJson(json{
            { "version", 1 }, { "id", 1 }, { "bookId", 2 }, { "type", "highlight" } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasAnnotationIssue(r.issues, "locator", "locator"));
    }
    {
        const pb::AnnotationResult r = pb::AnnotationFromJson(json{
            { "version", 1 },
            { "bookId", 2 },
            { "type", "pdf-annot" },
            { "locator", { { "version", 1 }, { "kind", "pdf" }, { "page", 1 } } } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasAnnotationIssue(r.issues, "type", "type"));
    }
    {
        const pb::AnnotationResult r = pb::ParseAnnotation("{broken");
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasAnnotationIssue(r.issues, "parse"));
    }
    {
        // locator 内部问题带 locator. 前缀
        const pb::AnnotationResult r = pb::AnnotationFromJson(json{
            { "version", 1 },
            { "bookId", 2 },
            { "type", "bookmark" },
            { "locator", { { "version", 1 }, { "kind", "epub" } } } });
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasAnnotationIssue(r.issues, "cfi", "locator.cfi"));
    }
}

void TestAnnotationValidation()
{
    // 颜色
    CHECK_TRUE(pb::IsValidAnnotationColor("#fbbf24"));
    CHECK_TRUE(pb::IsValidAnnotationColor("#FFF"));
    CHECK_TRUE(pb::IsValidAnnotationColor("#fbbf2480"));
    CHECK_TRUE(pb::IsValidAnnotationColor("rgb(251, 191, 36)"));
    CHECK_TRUE(pb::IsValidAnnotationColor("red"));
    CHECK_TRUE(pb::IsValidAnnotationColor("transparent"));
    CHECK_TRUE(!pb::IsValidAnnotationColor(""));
    CHECK_TRUE(!pb::IsValidAnnotationColor("#fbbf2"));
    CHECK_TRUE(!pb::IsValidAnnotationColor("#gggggg"));
    CHECK_TRUE(!pb::IsValidAnnotationColor("notacolor"));
    CHECK_TRUE(!pb::IsValidAnnotationColor("red;background:url(x)"));
    CHECK_TRUE(!pb::IsValidAnnotationColor("<script>"));
    CHECK_EQ(pb::NormalizeAnnotationColor("  #FBBF24 "), std::string("#fbbf24"));

    // 时间戳
    CHECK_TRUE(pb::IsIso8601Timestamp("2026-10-20T09:00:00.000Z"));
    CHECK_TRUE(pb::IsIso8601Timestamp("2026-10-20T09:00:00Z"));
    CHECK_TRUE(pb::IsIso8601Timestamp("2026-10-20T09:00:00+08:00"));
    CHECK_TRUE(pb::IsIso8601Timestamp("2026-10-20T09:00:00"));
    CHECK_TRUE(!pb::IsIso8601Timestamp("2026-13-20T09:00:00Z"));
    CHECK_TRUE(!pb::IsIso8601Timestamp("2026-10-32T09:00:00Z"));
    CHECK_TRUE(!pb::IsIso8601Timestamp("2026-10-20 09:00:00Z"));
    CHECK_TRUE(!pb::IsIso8601Timestamp("yesterday"));
    CHECK_TRUE(!pb::IsIso8601Timestamp(""));
    {
        const std::string now = pb::NowIso8601();
        CHECK_TRUE(!now.empty());
        CHECK_TRUE(pb::IsIso8601Timestamp(now));
    }

    // highlight：颜色必填且合法
    {
        pb::Annotation a = pb::MakeHighlight(1, pb::MakeEpubLocator("epubcfi(/6/4)"), "");
        a.createdAt = kCreated;
        a.updatedAt = kCreated;
        CHECK_TRUE(HasAnnotationIssue(pb::ValidateAnnotation(a), "color", "color"));
        CHECK_TRUE(!pb::IsValidAnnotation(a));

        a.color = "not a color";
        CHECK_TRUE(HasAnnotationIssue(pb::ValidateAnnotation(a), "color", "color"));

        a.color = "#34d399";
        CHECK_TRUE(pb::IsValidAnnotation(a));

        // Normalize 不会把坏颜色静默换成默认黄色（高亮必须报错）
        a.color = "not a color";
        const pb::AnnotationResult n = pb::NormalizeAnnotation(a);
        CHECK_TRUE(!n.ok);
        CHECK_TRUE(HasAnnotationIssue(n.issues, "color", "color"));
        CHECK_EQ(n.annotation.color, std::string("not a color"));
    }

    // note：正文必填
    {
        pb::Annotation a = pb::MakeNote(1, pb::MakeTextLocator(5), "   ");
        a.createdAt = kCreated;
        a.updatedAt = kCreated;
        CHECK_TRUE(HasAnnotationIssue(pb::ValidateAnnotation(a), "note", "note"));
        a.note = "正文";
        CHECK_TRUE(pb::IsValidAnnotation(a));
    }

    // bookmark：locator 必填；bookId 必填
    {
        pb::Annotation a = pb::MakeBookmark(0, pb::Locator());
        a.createdAt = kCreated;
        a.updatedAt = kCreated;
        const std::vector<pb::AnnotationIssue> issues = pb::ValidateAnnotation(a);
        CHECK_TRUE(HasAnnotationIssue(issues, "locator", "locator"));
        CHECK_TRUE(HasAnnotationIssue(issues, "book", "bookId"));
        CHECK_TRUE(!pb::IsValidAnnotation(a));
    }

    // 时间戳格式 / 倒序
    {
        pb::Annotation a = pb::MakeBookmark(1, pb::MakeTextLocator(1));
        a.createdAt = "yesterday";
        a.updatedAt = kUpdated;
        CHECK_TRUE(HasAnnotationIssue(pb::ValidateAnnotation(a), "timestamp", "createdAt"));

        a.createdAt = kUpdated;
        a.updatedAt = kCreated;  // 早于 createdAt
        CHECK_TRUE(HasAnnotationIssue(pb::ValidateAnnotation(a), "timestamp-order", "updatedAt"));

        const pb::AnnotationResult n = pb::NormalizeAnnotation(a);
        CHECK_TRUE(n.ok);
        CHECK_EQ(n.annotation.updatedAt, n.annotation.createdAt);
        CHECK_TRUE(HasAnnotationIssue(n.issues, "timestamp-order"));
    }

    // Normalize：补时间戳、tags 去重、relatedId 负数归零、非高亮坏颜色丢弃
    {
        pb::Annotation a = pb::MakeBookmark(1, pb::MakeTextLocator(2), "t");
        a.color = " nope ; ";
        a.tags = { "a", "A", " b ", "" };
        a.relatedId = -3;
        const pb::AnnotationResult n = pb::NormalizeAnnotation(a);
        CHECK_TRUE(n.ok);
        CHECK_TRUE(!n.annotation.createdAt.empty());
        CHECK_TRUE(HasAnnotationIssue(n.issues, "timestamp-filled"));
        CHECK_EQ(static_cast<int>(n.annotation.tags.size()), 2);
        CHECK_EQ(n.annotation.tags[0], std::string("a"));
        CHECK_EQ(n.annotation.tags[1], std::string("b"));
        CHECK_EQ(n.annotation.relatedId, 0);
        CHECK_TRUE(n.annotation.color.empty());
        CHECK_TRUE(HasAnnotationIssue(n.issues, "color"));
    }

    // 未知 type 不会被 Normalize 猜
    {
        pb::Annotation a;
        a.bookId = 1;
        a.locator = pb::MakeTextLocator(1);
        const pb::AnnotationResult n = pb::NormalizeAnnotation(a);
        CHECK_TRUE(!n.ok);
        CHECK_TRUE(HasAnnotationIssue(n.issues, "type", "type"));
    }
}

// ── Annotation：相等 / 排序 / 同一位置 ───────────────────────────────

void TestAnnotationOrdering()
{
    pb::Locator cfiA = pb::MakeEpubLocator("epubcfi(/6/4)");
    pb::Locator cfiB = pb::MakeEpubLocator("epubcfi(/6/6)");

    pb::Annotation a = pb::MakeBookmark(1, cfiA, "A");
    a.id = 3;
    a.createdAt = kCreated;
    a.updatedAt = kCreated;

    pb::Annotation b = pb::MakeHighlight(1, cfiA, "#fbbf24");  // 同书同位置，type 不同
    b.id = 1;
    b.createdAt = kCreated;
    b.updatedAt = kCreated;

    pb::Annotation c = pb::MakeBookmark(1, cfiB, "C");
    c.id = 2;
    c.createdAt = kCreated;
    c.updatedAt = kCreated;

    pb::Annotation d = pb::MakeBookmark(2, cfiA, "D");
    d.id = 0;
    d.createdAt = kCreated;
    d.updatedAt = kCreated;

    // 同一位置：bookId + type + locator
    CHECK_TRUE(pb::SamePosition(a, a));
    CHECK_TRUE(!pb::SamePosition(a, b));  // type 不同
    CHECK_TRUE(!pb::SamePosition(a, c));  // 位置不同
    CHECK_TRUE(!pb::SamePosition(a, d));  // 书不同

    // 同位置但其它字段不同：SamePosition=true，全等=false
    pb::Annotation a2 = a;
    a2.title = "改名";
    a2.note = "备注";
    CHECK_TRUE(pb::SamePosition(a, a2));
    CHECK_TRUE(a != a2);

    // 排序：bookId → locator → type → createdAt → id
    std::vector<pb::Annotation> list = { d, c, b, a };
    std::sort(list.begin(), list.end());
    CHECK_EQ(list[0].id, 3);  // book1 / cfiA / bookmark
    CHECK_EQ(list[1].id, 1);  // book1 / cfiA / highlight
    CHECK_EQ(list[2].id, 2);  // book1 / cfiB
    CHECK_EQ(list[3].id, 0);  // book2

    CHECK_EQ(pb::CompareAnnotation(a, a), 0);
    CHECK_EQ(pb::CompareAnnotation(a, b), -1);
    CHECK_EQ(pb::CompareAnnotation(b, a), 1);
    CHECK_EQ(pb::CompareAnnotation(c, d), -1);

    // 归一化后的副本全等（round-trip 语义）
    pb::Annotation source = pb::MakeHighlight(5, cfiA, "#34d399", "文本");
    source.createdAt = kCreated;
    source.updatedAt = kUpdated;
    const pb::AnnotationResult n = pb::NormalizeAnnotation(source);
    CHECK_TRUE(n.ok);
    CHECK_TRUE(n.annotation == source);
}

// ── 旧 DB 行迁移 ─────────────────────────────────────────────────────

void TestLegacyMigration()
{
    // bookmark（TXT）：cfi = pos-<offset>，page 是百分比
    {
        const json row = {
            { "id", 5 },
            { "book_id", 3 },
            { "cfi", "pos-1200" },
            { "page", 37.5 },
            { "progress", 37.5 },
            { "title", "书签 1" },
            { "note", "看到这里" },
            { "created_at", kCreated },
        };
        const pb::AnnotationResult r = pb::FromLegacyBookmark(row, pb::LegacyBookFormat::Text);
        CHECK_TRUE(r.ok);
        CHECK_TRUE(r.annotation.type == pb::AnnotationType::Bookmark);
        CHECK_EQ(r.annotation.id, 5);
        CHECK_EQ(r.annotation.bookId, 3);
        CHECK_EQ(r.annotation.title, std::string("书签 1"));
        CHECK_EQ(r.annotation.note, std::string("看到这里"));
        CHECK_TRUE(r.annotation.locator.kind == pb::LocatorKind::Text);
        CHECK_EQ(r.annotation.locator.offset, 1200);
        CHECK_EQ(r.annotation.createdAt, std::string(kCreated));
    }

    // bookmark（HTML）：cfi 实际是元素 id → html href
    {
        const json row = {
            { "id", 6 },
            { "book_id", 4 },
            { "cfi", "chapter-2" },
            { "created_at", kCreated },
        };
        const pb::AnnotationResult r = pb::FromLegacyBookmark(row, pb::LegacyBookFormat::Html);
        CHECK_TRUE(r.ok);
        CHECK_TRUE(r.annotation.locator.kind == pb::LocatorKind::Html);
        CHECK_EQ(r.annotation.locator.href, std::string("chapter-2"));
    }

    // highlight（EPUB）：text → locator.quote
    {
        const json row = {
            { "id", 7 },
            { "book_id", 3 },
            { "cfi", "epubcfi(/6/4[c01]!/4/2/1:0)" },
            { "text", "被高亮的句子" },
            { "color", "#FBBF24" },
            { "note", "我的批注" },
            { "created_at", kCreated },
        };
        const pb::AnnotationResult r = pb::FromLegacyHighlight(row, pb::LegacyBookFormat::Epub);
        CHECK_TRUE(r.ok);
        CHECK_TRUE(r.annotation.type == pb::AnnotationType::Highlight);
        CHECK_TRUE(r.annotation.locator.kind == pb::LocatorKind::Epub);
        CHECK_EQ(r.annotation.locator.quote, std::string("被高亮的句子"));
        CHECK_EQ(r.annotation.color, std::string("#fbbf24"));  // Normalize 统一小写
        CHECK_EQ(r.annotation.note, std::string("我的批注"));
    }

    // highlight（TXT）：pos-<offset> + 百分比 page
    {
        const json row = {
            { "id", 8 },
            { "book_id", 5 },
            { "cfi", "pos-42" },
            { "page", 12.5 },
            { "text", "选中文字" },
            { "color", "#34d399" },
            { "created_at", kCreated },
        };
        const pb::AnnotationResult r = pb::FromLegacyHighlight(row, pb::LegacyBookFormat::Text);
        CHECK_TRUE(r.ok);
        CHECK_TRUE(r.annotation.locator.kind == pb::LocatorKind::Text);
        CHECK_EQ(r.annotation.locator.offset, 42);
        CHECK_EQ(r.annotation.locator.quote, std::string("选中文字"));
    }

    // 旧 highlight 没有颜色：迁移报错（不许静默变白）
    {
        const json row = {
            { "book_id", 5 },
            { "cfi", "epubcfi(/6/4)" },
            { "text", "x" },
            { "created_at", kCreated },
        };
        const pb::AnnotationResult r = pb::FromLegacyHighlight(row, pb::LegacyBookFormat::Epub);
        CHECK_TRUE(!r.ok);
        CHECK_TRUE(HasAnnotationIssue(r.issues, "color", "color"));
    }

    // note：只有 highlight_id 没有位置 → 先报错；传入关联高亮后继承位置
    {
        const pb::Annotation related = [] {
            pb::Annotation h = pb::MakeHighlight(3, pb::MakeEpubLocator("epubcfi(/6/4[c01])"),
                                                 "#fbbf24", "被高亮的句子");
            h.id = 7;
            h.createdAt = kCreated;
            h.updatedAt = kCreated;
            return h;
        }();

        const json row = {
            { "id", 9 },
            { "book_id", 3 },
            { "highlight_id", 7 },
            { "content", "笔记正文" },
            { "created_at", kCreated },
            { "updated_at", kUpdated },
        };

        const pb::AnnotationResult lonely =
            pb::FromLegacyNote(row, pb::LegacyBookFormat::Epub);
        CHECK_TRUE(!lonely.ok);  // 没有位置
        CHECK_EQ(lonely.annotation.relatedId, 7);
        CHECK_EQ(lonely.annotation.note, std::string("笔记正文"));

        const pb::AnnotationResult r =
            pb::FromLegacyNote(row, pb::LegacyBookFormat::Epub, &related);
        CHECK_TRUE(r.ok);
        CHECK_TRUE(r.annotation.type == pb::AnnotationType::Note);
        CHECK_TRUE(r.annotation.locator.kind == pb::LocatorKind::Epub);
        CHECK_EQ(r.annotation.locator.cfi, std::string("epubcfi(/6/4[c01])"));
        CHECK_EQ(r.annotation.note, std::string("笔记正文"));
        CHECK_EQ(r.annotation.relatedId, 7);
        CHECK_EQ(r.annotation.updatedAt, std::string(kUpdated));
        CHECK_TRUE(HasAnnotationIssue(r.issues, "locator-inherited"));
    }

    // 进度行：只有 progress + scroll_position → scroll 百分比
    {
        const json row = {
            { "book_id", 3 },
            { "progress", 42.5 },
            { "scroll_position", 1234 },
            { "updated_at", kUpdated },
        };
        const pb::Locator position =
            pb::LocatorFromLegacyProgress(row, pb::LegacyBookFormat::Html);
        CHECK_TRUE(position.kind == pb::LocatorKind::Scroll);
        CHECK_TRUE(Near(position.percent, 42.5));
        const pb::Locator position2 =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Html);
        CHECK_TRUE(pb::SamePosition(position, position2));
    }

    // PDF 行：page + rect（对象）
    {
        const json row = {
            { "page", 12 },
            { "rect", { { "x", 0.1 }, { "y", 0.2 }, { "w", 0.3 }, { "h", 0.05 } } },
        };
        const pb::Locator position =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Pdf);
        CHECK_TRUE(position.kind == pb::LocatorKind::Pdf);
        CHECK_EQ(position.page, 12);
        CHECK_TRUE(position.hasRect);
    }

    // PDF 行：rect 用数组；分数页码四舍五入并给 Warning
    {
        const json row = {
            { "page", 12.4 },
            { "rect", { 0.0, 0.0, 1.0, 0.1 } },
        };
        std::vector<pb::LocatorIssue> issues;
        const pb::Locator position =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Pdf, &issues);
        CHECK_TRUE(position.kind == pb::LocatorKind::Pdf);
        CHECK_EQ(position.page, 12);
        CHECK_TRUE(position.hasRect);
        CHECK_TRUE(HasLocatorIssue(issues, "legacy-page"));
    }

    // 越界 rect：丢弃 + Warning
    {
        const json row = {
            { "page", 2 },
            { "rect", { { "x", 2.0 }, { "y", 0.0 }, { "w", 0.1 }, { "h", 0.1 } } },
        };
        std::vector<pb::LocatorIssue> issues;
        const pb::Locator position =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Pdf, &issues);
        CHECK_TRUE(!position.hasRect);
        CHECK_TRUE(HasLocatorIssue(issues, "legacy-rect"));
    }

    // page 0 是「未定位」哨兵：不产生 pdf 第 0 页，继续走 progress
    {
        const json row = { { "page", 0 }, { "progress", 10.0 } };
        const pb::Locator position =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Text);
        CHECK_TRUE(position.kind == pb::LocatorKind::Scroll);
        CHECK_TRUE(Near(position.percent, 10.0));
    }

    // 格式未知 + 整数 page：按页码猜测并给 Warning（让迁移层复核）
    {
        const json row = { { "page", 3 } };
        std::vector<pb::LocatorIssue> issues;
        const pb::Locator position =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Unknown, &issues);
        CHECK_TRUE(position.kind == pb::LocatorKind::Pdf);
        CHECK_EQ(position.page, 3);
        CHECK_TRUE(HasLocatorIssue(issues, "legacy-page"));
    }

    // 什么都没有：空 locator + Warning，不抛异常
    {
        const json row = { { "book_id", 1 } };
        std::vector<pb::LocatorIssue> issues;
        const pb::Locator position = pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Epub, &issues);
        CHECK_TRUE(position.IsEmpty());
        CHECK_TRUE(HasLocatorIssue(issues, "legacy-position"));
    }

    // null cfi 不报错（旧行里常见 "cfi": null）
    {
        const json row = { { "cfi", nullptr }, { "page", 2 } };
        const pb::Locator position =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Pdf);
        CHECK_TRUE(position.kind == pb::LocatorKind::Pdf);
        CHECK_EQ(position.page, 2);
    }

    // 非对象输入：不抛异常，给出 Warning
    {
        std::vector<pb::LocatorIssue> issues;
        const pb::Locator position = pb::LocatorFromLegacyPosition(json::array(), pb::LegacyBookFormat::Epub, &issues);
        CHECK_TRUE(position.IsEmpty());
        CHECK_TRUE(HasLocatorIssue(issues, "legacy-root"));
    }

    // TXT 行里 cfi 不是 pos-：不猜成 epub CFI，给 Warning
    {
        const json row = { { "cfi", "garbage" } };
        std::vector<pb::LocatorIssue> issues;
        const pb::Locator position =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Text, &issues);
        CHECK_TRUE(position.IsEmpty());
        CHECK_TRUE(HasLocatorIssue(issues, "legacy-cfi"));
    }

    // URL / 文本不存在：旧行 text 字段只进 quote
    {
        const json row = { { "page", 5 }, { "text", " 选中 " } };
        const pb::Locator position =
            pb::LocatorFromLegacyPosition(row, pb::LegacyBookFormat::Pdf);
        CHECK_EQ(position.quote, std::string("选中"));
    }
}

} // namespace

int main()
{
    TestLocatorRoundTrip();
    TestLocatorValidationAndNormalize();
    TestLocatorJsonErrors();
    TestAnnotationRoundTrip();
    TestAnnotationValidation();
    TestAnnotationOrdering();
    TestLegacyMigration();
    return pb_t::Summary("annotation_test");
}
