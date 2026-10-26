// ── PB-010：DictionaryService 单元测试 ────────────────────────────
// 不依赖 Win32 GUI / WebView2 / BridgeServer：直接链接 DictionaryService.cpp +
// third_party/miniz*.c（见 CMake 的 dictionary_service_test）。
//
// 覆盖（对应 docs/BACKLOG.md PB-010 MVP 验收）：
//   * ECDICT CSV 导入：表头跳过、引号 / 转义 / 字段内换行、字段映射、条目数
//   * 查词：精确、大小写不敏感 / 敏感、前缀、缺失、空查询、未加载
//   * 词形还原：后缀猜原形 + 用 exchange 反向验证（going → go）；
//     exchange 的 "0:lemma"（went → go）；ParseExchange
//   * StarDict：.ifo/.idx/.dict（sametypesequence 与逐字段类型前缀两种布局）、
//     .idx.gz/.dict.dz（手写 stored-deflate gzip 包装）
//   * 坏文件：不存在的 CSV / ifo、空 CSV、未闭合引号、坏 ifo、越界 idx
//   * 上限 / 取消 / 进度：maxEntries、cancel 标志、进度回调
//   * 缓存：SaveCache → 新实例 LoadCache 内容一致；损坏 / 版本不符 / 不存在被拒绝
//   * Clear / Info / Suggest / MatchKindName
//
// 每个用例用自己的临时目录，绝不碰真实用户词库。

#include "services/DictionaryService.h"
#include "utils/test_assert.h"

#include <atomic>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

namespace fs = std::filesystem;
using pb::DictionaryImportOptions;
using pb::DictionaryLookupOptions;
using pb::DictionaryMatchKind;
using pb::DictionaryService;
using pb::DictionarySourceKind;

namespace {

fs::path MakeScratch(const char* name)
{
    const fs::path dir = fs::temp_directory_path() / "pb_dict_test" / name;
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir, ec);
    return dir;
}

std::string U8(const fs::path& path) { return path.u8string(); }

void WriteText(const fs::path& path, const std::string& text)
{
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    file.write(text.data(), static_cast<std::streamsize>(text.size()));
}

std::string ReadText(const fs::path& path)
{
    std::ifstream file(path, std::ios::binary);
    if (!file) return std::string();
    return std::string((std::istreambuf_iterator<char>(file)),
                       std::istreambuf_iterator<char>());
}

bool CodeIs(const std::string& code, const char* expected) { return code == expected; }

bool Contains(const std::string& text, const std::string& needle)
{
    return text.find(needle) != std::string::npos;
}

bool HasHit(const pb::DictionaryLookupResult& result, const std::string& word,
            DictionaryMatchKind kind)
{
    for (const pb::DictionaryHit& hit : result.hits) {
        if (hit.entry.word == word && hit.kind == kind) return true;
    }
    return false;
}

// ECDICT 的 13 列（word,phonetic,definition,translation,pos,collins,oxford,
// tag,bnc,frq,exchange,detail,audio），用最朴素的方式拼，方便读。
std::string EcdictRow(const std::string& word, const std::string& phonetic,
                      const std::string& definition, const std::string& translation,
                      const std::string& pos, const std::string& tag,
                      const std::string& exchange)
{
    return word + "," + phonetic + "," + definition + "," + translation + "," + pos +
           ",3,1," + tag + ",100,200," + exchange + ",,";
}

std::string BigSampleCsv()
{
    return "word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio\n" +
           EcdictRow("hello", "həˈləʊ", "\"used as a greeting, when meeting\"",
                     "你好；喂", "int", "zk cet4 cet6", "") + "\n" +
           EcdictRow("help", "help", "to assist", "帮助", "v:80/n:20", "cet4",
                     "p:helped/d:helped/i:helping/3:helps") + "\n" +
           EcdictRow("go", "ɡəʊ", "to move", "去", "v", "cet4",
                     "p:went/d:gone/i:going/3:goes/s:goes") + "\n" +
           EcdictRow("went", "went", "", "去（过去式）", "v", "", "0:go") + "\n";
}

// ── StarDict 构造 ─────────────────────────────────────────────────
std::string Be32(std::uint32_t value)
{
    std::string out(4, '\0');
    out[0] = static_cast<char>((value >> 24) & 0xFF);
    out[1] = static_cast<char>((value >> 16) & 0xFF);
    out[2] = static_cast<char>((value >> 8) & 0xFF);
    out[3] = static_cast<char>(value & 0xFF);
    return out;
}

// 用 stored（不压缩）deflate 块拼一个合法 gzip：不引入压缩器，但仍走真
// deflate 解压路径；头部故意带 FEXTRA（模仿 dictzip 的 RA 字段）。
std::string GzipStored(const std::string& data)
{
    std::string out;
    out.push_back(0x1F);
    out.push_back(static_cast<char>(0x8B));
    out.push_back(8);   // deflate
    out.push_back(4);   // FLG = FEXTRA
    for (int i = 0; i < 4; ++i) out.push_back(0);  // MTIME
    out.push_back(0);                              // XFL
    out.push_back(static_cast<char>(0xFF));        // OS
    out.push_back(2);  // XLEN = 2（一个空的 extra 字段）
    out.push_back(0);
    out.push_back(0);
    out.push_back(0);

    std::size_t position = 0;
    do {
        std::size_t chunk = data.size() - position;
        if (chunk > 65535) chunk = 65535;
        const bool last = (position + chunk >= data.size());
        out.push_back(last ? 1 : 0);  // BFINAL + BTYPE=00(stored)
        const std::uint16_t length = static_cast<std::uint16_t>(chunk);
        const std::uint16_t complement = static_cast<std::uint16_t>(~length);
        out.push_back(static_cast<char>(length & 0xFF));
        out.push_back(static_cast<char>((length >> 8) & 0xFF));
        out.push_back(static_cast<char>(complement & 0xFF));
        out.push_back(static_cast<char>((complement >> 8) & 0xFF));
        out.append(data, position, chunk);
        position += chunk;
    } while (position < data.size());

    for (int i = 0; i < 8; ++i) out.push_back(0);  // CRC32 + ISIZE（本服务不校验）
    return out;
}

struct StarDictSpec {
    std::string base;                                // 不含扩展名
    std::string sameTypeSequence;                    // 空 = 逐字段类型前缀
    std::vector<std::pair<std::string, std::string>> entries;  // word → .dict 数据
    bool gzip = false;                               // .idx.gz + .dict.dz
    bool idxOffset64 = false;                        // idxoffsetbits=64（8 字节偏移）
    bool writeIfo = true;
    std::string ifoOverride;                         // 非空则用它当 ifo 内容
    bool corruptIdxOffset = false;                   // 故意让 idx 指向 dict 之外
};

bool WriteStarDict(const fs::path& dir, const StarDictSpec& spec)
{
    std::string dict;
    std::string idx;
    for (const auto& entry : spec.entries) {
        idx += entry.first;
        idx.push_back('\0');
        const std::uint32_t offset = static_cast<std::uint32_t>(dict.size());
        const std::uint32_t size = static_cast<std::uint32_t>(entry.second.size());
        if (spec.corruptIdxOffset) {
            idx += Be32(0xFFFFFF00u);
        } else if (spec.idxOffset64) {
            idx += Be32(0);  // 高 32 位
            idx += Be32(offset);
        } else {
            idx += Be32(offset);
        }
        idx += Be32(size);
        dict += entry.second;
    }

    if (spec.writeIfo) {
        std::string ifo = "StarDict's dict ifo file\nversion=2.4.2\nbookname=" +
                          spec.base + "\nwordcount=" +
                          std::to_string(spec.entries.size()) + "\nidxfilesize=" +
                          std::to_string(idx.size()) + "\n";
        if (!spec.sameTypeSequence.empty()) {
            ifo += "sametypesequence=" + spec.sameTypeSequence + "\n";
        }
        if (spec.idxOffset64) ifo += "idxoffsetbits=64\n";
        WriteText(dir / (spec.base + ".ifo"),
                  spec.ifoOverride.empty() ? ifo : spec.ifoOverride);
    }
    if (spec.gzip) {
        WriteText(dir / (spec.base + ".idx.gz"), GzipStored(idx));
        WriteText(dir / (spec.base + ".dict.dz"), GzipStored(dict));
    } else {
        WriteText(dir / (spec.base + ".idx"), idx);
        WriteText(dir / (spec.base + ".dict"), dict);
    }
    return true;
}

} // namespace

int main()
{
    // ── 1. ECDICT CSV 导入 + Info ─────────────────────────────────────
    const fs::path rootCsvDir = MakeScratch("ecdict");
    const fs::path csvPath = rootCsvDir / "ecdict.csv";
    WriteText(csvPath, BigSampleCsv());

    DictionaryService dict;
    {
        const pb::DictionaryInfo before = dict.Info();
        CHECK_TRUE(!before.loaded);
        CHECK_EQ(before.entries, static_cast<std::size_t>(0));

        int progressCalls = 0;
        pb::DictionaryProgress lastProgress;
        DictionaryImportOptions options;
        const pb::DictionaryImportResult imported = dict.ImportEcdictCsv(
            U8(csvPath), options,
            [&](const pb::DictionaryProgress& update) {
                ++progressCalls;
                lastProgress = update;
            });
        CHECK_TRUE(imported.ok);
        CHECK_TRUE(CodeIs(imported.code, pb::dictionary_codes::kOk));
        CHECK_EQ(imported.entries, static_cast<std::size_t>(4));
        CHECK_TRUE(imported.bytes > 0);
        CHECK_EQ(imported.source, DictionarySourceKind::EcdictCsv);
        CHECK_TRUE(progressCalls >= 2);
        CHECK_TRUE(CodeIs(lastProgress.stage, "indexing"));

        const pb::DictionaryInfo info = dict.Info();
        CHECK_TRUE(info.loaded);
        CHECK_EQ(info.entries, static_cast<std::size_t>(4));
        CHECK_EQ(info.source, DictionarySourceKind::EcdictCsv);
        CHECK_TRUE(info.bytes > 0);
        CHECK_TRUE(!dict.Empty());
    }

    // ── 2. 精确 / 大小写 / 字段 ───────────────────────────────────────
    {
        const pb::DictionaryLookupResult exact = dict.Lookup("hello");
        CHECK_TRUE(exact.ok);
        CHECK_TRUE(exact.found);
        CHECK_TRUE(CodeIs(exact.code, pb::dictionary_codes::kOk));
        CHECK_EQ(exact.hits.size(), static_cast<std::size_t>(1));
        if (!exact.hits.empty()) {
            const pb::DictionaryEntry& entry = exact.hits[0].entry;
            CHECK_EQ(entry.word, std::string("hello"));
            CHECK_EQ(entry.phonetic, std::string("həˈləʊ"));
            CHECK_TRUE(Contains(entry.translation, "你好"));
            CHECK_EQ(entry.pos, std::string("int"));
            CHECK_TRUE(Contains(entry.tag, "cet4"));
            CHECK_TRUE(entry.exchange.empty());
            CHECK_EQ(exact.hits[0].kind, DictionaryMatchKind::Exact);
        }

        const pb::DictionaryLookupResult mixedCase = dict.Lookup("HeLLo");
        CHECK_TRUE(mixedCase.ok);
        CHECK_TRUE(mixedCase.found);
        CHECK_TRUE(HasHit(mixedCase, "hello", DictionaryMatchKind::Exact));

        DictionaryLookupOptions caseSensitive;
        caseSensitive.caseInsensitive = false;
        const pb::DictionaryLookupResult sensitive = dict.Lookup("HeLLo", caseSensitive);
        CHECK_TRUE(sensitive.ok);
        CHECK_TRUE(!sensitive.found);
        CHECK_TRUE(CodeIs(sensitive.code, pb::dictionary_codes::kNotFound));

        const pb::DictionaryLookupResult exactSensitive = dict.Lookup("hello", caseSensitive);
        CHECK_TRUE(exactSensitive.found);
    }

    // ── 3. 前缀 + Suggest ────────────────────────────────────────────
    {
        const pb::DictionaryLookupResult noPrefix = dict.Lookup("hel");
        CHECK_TRUE(noPrefix.ok);
        CHECK_TRUE(!noPrefix.found);

        DictionaryLookupOptions withPrefix;
        withPrefix.prefix = true;
        const pb::DictionaryLookupResult prefixed = dict.Lookup("hel", withPrefix);
        CHECK_TRUE(prefixed.found);
        CHECK_EQ(prefixed.hits.size(), static_cast<std::size_t>(2));
        CHECK_TRUE(HasHit(prefixed, "hello", DictionaryMatchKind::Prefix));
        CHECK_TRUE(HasHit(prefixed, "help", DictionaryMatchKind::Prefix));

        const std::vector<std::string> suggestions = dict.Suggest("he", 5);
        CHECK_EQ(suggestions.size(), static_cast<std::size_t>(2));
        if (suggestions.size() == 2) {
            CHECK_EQ(suggestions[0], std::string("hello"));
            CHECK_EQ(suggestions[1], std::string("help"));
        }
    }

    // ── 4. 词形还原（后缀规则 + exchange 反向验证 + 0:lemma）────────────
    {
        // going 不在词库里 → 猜原形 go，且 go 的 exchange 含 going → verified
        const pb::DictionaryLookupResult going = dict.Lookup("going");
        CHECK_TRUE(going.ok);
        CHECK_TRUE(going.found);
        CHECK_TRUE(HasHit(going, "go", DictionaryMatchKind::Morphology));
        if (!going.hits.empty()) {
            CHECK_TRUE(going.hits[0].verified);
            CHECK_EQ(going.hits[0].matched, std::string("go"));
        }

        const pb::DictionaryLookupResult goes = dict.Lookup("goes");
        CHECK_TRUE(goes.found);
        CHECK_TRUE(HasHit(goes, "go", DictionaryMatchKind::Morphology));

        // went 本身是词条，且 exchange 里带 "0:go" → 附带原形
        const pb::DictionaryLookupResult went = dict.Lookup("went");
        CHECK_TRUE(went.found);
        CHECK_TRUE(HasHit(went, "went", DictionaryMatchKind::Exact));
        CHECK_TRUE(HasHit(went, "go", DictionaryMatchKind::Morphology));

        // 形态还原可以关掉
        DictionaryLookupOptions noMorphology;
        noMorphology.morphology = false;
        const pb::DictionaryLookupResult goingPlain = dict.Lookup("going", noMorphology);
        CHECK_TRUE(goingPlain.ok);
        CHECK_TRUE(!goingPlain.found);

        const pb::DictionaryLookupResult missing = dict.Lookup("xyzzyq");
        CHECK_TRUE(missing.ok);
        CHECK_TRUE(!missing.found);
        CHECK_TRUE(CodeIs(missing.code, pb::dictionary_codes::kNotFound));

        // ParseExchange
        const auto forms = DictionaryService::ParseExchange("p:went/d:gone/i:going/0:go");
        CHECK_EQ(forms.size(), static_cast<std::size_t>(4));
        if (forms.size() == 4) {
            CHECK_EQ(forms[0].first, std::string("p"));
            CHECK_EQ(forms[0].second, std::string("went"));
            CHECK_EQ(forms[3].first, std::string("0"));
            CHECK_EQ(forms[3].second, std::string("go"));
        }
        CHECK_EQ(std::string(DictionaryService::MatchKindName(DictionaryMatchKind::Exact)),
                 std::string("exact"));
        CHECK_EQ(std::string(DictionaryService::MatchKindName(DictionaryMatchKind::Prefix)),
                 std::string("prefix"));
        CHECK_EQ(std::string(DictionaryService::MatchKindName(DictionaryMatchKind::Morphology)),
                 std::string("morphology"));
    }

    // ── 5. 空查询 / 未加载 / Clear ────────────────────────────────────
    {
        const pb::DictionaryLookupResult emptyQuery = dict.Lookup("   ");
        CHECK_TRUE(!emptyQuery.ok);
        CHECK_TRUE(CodeIs(emptyQuery.code, pb::dictionary_codes::kEmptyQuery));

        DictionaryService fresh;
        const pb::DictionaryLookupResult notLoaded = fresh.Lookup("hello");
        CHECK_TRUE(!notLoaded.ok);
        CHECK_TRUE(CodeIs(notLoaded.code, pb::dictionary_codes::kNotLoaded));

        DictionaryService cleared;
        CHECK_TRUE(cleared.ImportEcdictCsv(U8(csvPath)).ok);
        CHECK_TRUE(!cleared.Empty());
        cleared.Clear();
        CHECK_TRUE(cleared.Empty());
        CHECK_EQ(cleared.Size(), static_cast<std::size_t>(0));
        CHECK_TRUE(CodeIs(cleared.Lookup("hello").code, pb::dictionary_codes::kNotLoaded));
    }

    // ── 6. 引号 / 转义 / 字段内换行 ───────────────────────────────────
    {
        const fs::path dir = MakeScratch("quoted");
        const fs::path path = dir / "quoted.csv";
        const std::string csv =
            "word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio\n"
            "quote,\"kwəʊt\",\"line1, \"\"quoted\"\", line2\",\"引用\",n,,,,,,,\n"
            "multi,,\"first\nsecond\",\"多行\",n,,,,,,,\n";
        WriteText(path, csv);

        DictionaryService quoted;
        const pb::DictionaryImportResult imported = quoted.ImportEcdictCsv(U8(path));
        CHECK_TRUE(imported.ok);
        CHECK_EQ(imported.entries, static_cast<std::size_t>(2));

        const pb::DictionaryLookupResult quote = quoted.Lookup("quote");
        CHECK_TRUE(quote.found);
        if (!quote.hits.empty()) {
            CHECK_TRUE(Contains(quote.hits[0].entry.definition, "line1"));
            CHECK_TRUE(Contains(quote.hits[0].entry.definition, "\"quoted\""));
            CHECK_TRUE(Contains(quote.hits[0].entry.definition, "line2"));
        }
        const pb::DictionaryLookupResult multi = quoted.Lookup("multi");
        CHECK_TRUE(multi.found);
        if (!multi.hits.empty()) {
            CHECK_TRUE(Contains(multi.hits[0].entry.definition, "first"));
            CHECK_TRUE(Contains(multi.hits[0].entry.definition, "second"));
        }
    }

    // ── 7. 坏 CSV / 上限 / 取消 / 进度 ───────────────────────────────
    {
        const fs::path dir = MakeScratch("bad-csv");

        const pb::DictionaryImportResult missing =
            dict.ImportEcdictCsv(U8(dir / "nope.csv"));
        CHECK_TRUE(!missing.ok);
        CHECK_TRUE(CodeIs(missing.code, pb::dictionary_codes::kFileMissing));

        WriteText(dir / "empty.csv", std::string());
        const pb::DictionaryImportResult emptyFile =
            dict.ImportEcdictCsv(U8(dir / "empty.csv"));
        CHECK_TRUE(!emptyFile.ok);
        CHECK_TRUE(CodeIs(emptyFile.code, pb::dictionary_codes::kEmpty));

        WriteText(dir / "header.csv",
                  "word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,"
                  "exchange,detail,audio\n");
        const pb::DictionaryImportResult headerOnly =
            dict.ImportEcdictCsv(U8(dir / "header.csv"));
        CHECK_TRUE(!headerOnly.ok);
        CHECK_TRUE(CodeIs(headerOnly.code, pb::dictionary_codes::kEmpty));

        WriteText(dir / "broken.csv", "word,phonetic,definition,translation\nbad,\"unterminated\n");
        const pb::DictionaryImportResult broken =
            dict.ImportEcdictCsv(U8(dir / "broken.csv"));
        CHECK_TRUE(!broken.ok);
        CHECK_TRUE(CodeIs(broken.code, pb::dictionary_codes::kBadCsv));

        // 上限：maxEntries=1，两条数据 → too_large，且已加载的词典不变
        DictionaryImportOptions tiny;
        tiny.maxEntries = 1;
        const pb::DictionaryImportResult tooMany =
            dict.ImportEcdictCsv(U8(csvPath), tiny);
        CHECK_TRUE(!tooMany.ok);
        CHECK_TRUE(CodeIs(tooMany.code, pb::dictionary_codes::kTooLarge));
        CHECK_TRUE(dict.Lookup("hello").found);  // 旧词典仍然在

        // 取消：标志一开始就是 true
        std::atomic<bool> cancel(true);
        const pb::DictionaryImportResult cancelled =
            dict.ImportEcdictCsv(U8(csvPath), DictionaryImportOptions(), nullptr, &cancel);
        CHECK_TRUE(!cancelled.ok);
        CHECK_TRUE(CodeIs(cancelled.code, pb::dictionary_codes::kCancelled));
        CHECK_TRUE(dict.Lookup("hello").found);

        // 进度回调：至少被调用过，bytesTotal 有值
        std::atomic<bool> never(false);
        int calls = 0;
        std::int64_t totalBytes = 0;
        CHECK_TRUE(dict.ImportEcdictCsv(
                       U8(csvPath), DictionaryImportOptions(),
                       [&](const pb::DictionaryProgress& update) {
                           ++calls;
                           if (update.bytesTotal > 0) totalBytes = update.bytesTotal;
                       },
                       &never)
                       .ok);
        CHECK_TRUE(calls >= 2);
        CHECK_TRUE(totalBytes > 0);
    }

    // ── 8. StarDict：sametypesequence=mt ─────────────────────────────
    {
        const fs::path dir = MakeScratch("stardict");
        StarDictSpec spec;
        spec.base = "mini";
        spec.sameTypeSequence = "mt";
        spec.entries.push_back({"apple", std::string("苹果") + '\0' + "ˈæpl"});
        spec.entries.push_back({"banana", std::string("香蕉") + '\0' + "bəˈnɑːnə"});
        CHECK_TRUE(WriteStarDict(dir, spec));

        DictionaryService star;
        const pb::DictionaryImportResult imported =
            star.ImportStarDict(U8(dir / "mini.ifo"));
        CHECK_TRUE(imported.ok);
        CHECK_TRUE(CodeIs(imported.code, pb::dictionary_codes::kOk));
        CHECK_EQ(imported.entries, static_cast<std::size_t>(2));
        CHECK_EQ(imported.source, DictionarySourceKind::StarDict);
        CHECK_EQ(star.Info().source, DictionarySourceKind::StarDict);

        const pb::DictionaryLookupResult apple = star.Lookup("apple");
        CHECK_TRUE(apple.found);
        if (!apple.hits.empty()) {
            CHECK_EQ(apple.hits[0].entry.word, std::string("apple"));
            CHECK_TRUE(Contains(apple.hits[0].entry.translation, "苹果"));
            CHECK_TRUE(Contains(apple.hits[0].entry.phonetic, "æpl"));
        }

        // 逐字段类型前缀布局（无 sametypesequence）：'t'+音标\0+'m'+释义
        StarDictSpec prefixed;
        prefixed.base = "prefix";
        prefixed.entries.push_back({"cat", std::string("t") + "kæt" + '\0' + "m" + "猫"});
        CHECK_TRUE(WriteStarDict(dir, prefixed));
        CHECK_TRUE(star.ImportStarDict(U8(dir / "prefix.ifo")).ok);
        const pb::DictionaryLookupResult cat = star.Lookup("cat");
        CHECK_TRUE(cat.found);
        if (!cat.hits.empty()) {
            CHECK_TRUE(Contains(cat.hits[0].entry.translation, "猫"));
            CHECK_TRUE(Contains(cat.hits[0].entry.phonetic, "kæt"));
        }
    }

    // ── 9. StarDict：gzip（.idx.gz + .dict.dz）───────────────────────
    {
        const fs::path dir = MakeScratch("stardict-gz");
        StarDictSpec spec;
        spec.base = "gz";
        spec.sameTypeSequence = "mt";
        spec.gzip = true;
        spec.entries.push_back({"gzip", std::string("压缩") + '\0' + "ɡzɪp"});
        CHECK_TRUE(WriteStarDict(dir, spec));

        DictionaryService star;
        const pb::DictionaryImportResult imported =
            star.ImportStarDict(U8(dir / "gz.ifo"));
        CHECK_TRUE(imported.ok);
        CHECK_EQ(imported.entries, static_cast<std::size_t>(1));
        const pb::DictionaryLookupResult hit = star.Lookup("gzip");
        CHECK_TRUE(hit.found);
        if (!hit.hits.empty()) {
            CHECK_TRUE(Contains(hit.hits[0].entry.translation, "压缩"));
        }
    }

    // ── 10. StarDict：坏文件 ─────────────────────────────────────────
    {
        const fs::path dir = MakeScratch("stardict-bad");
        DictionaryService star;

        CHECK_TRUE(CodeIs(star.ImportStarDict(U8(dir / "nope.ifo")).code,
                          pb::dictionary_codes::kFileMissing));

        StarDictSpec badIfo;
        badIfo.base = "badifo";
        badIfo.ifoOverride = "this is not a stardict ifo file\n";
        badIfo.entries.push_back({"word", "text"});
        CHECK_TRUE(WriteStarDict(dir, badIfo));
        CHECK_TRUE(CodeIs(star.ImportStarDict(U8(dir / "badifo.ifo")).code,
                          pb::dictionary_codes::kBadIfo));

        StarDictSpec outOfRange;
        outOfRange.base = "badidx";
        outOfRange.sameTypeSequence = "m";
        outOfRange.corruptIdxOffset = true;
        outOfRange.entries.push_back({"word", "text"});
        CHECK_TRUE(WriteStarDict(dir, outOfRange));
        CHECK_TRUE(CodeIs(star.ImportStarDict(U8(dir / "badidx.ifo")).code,
                          pb::dictionary_codes::kBadDict));

        StarDictSpec noIdx;
        noIdx.base = "noidx";
        noIdx.entries.push_back({"word", "text"});
        CHECK_TRUE(WriteStarDict(dir, noIdx));
        std::error_code ec;
        fs::remove(dir / "noidx.idx", ec);
        CHECK_TRUE(CodeIs(star.ImportStarDict(U8(dir / "noidx.ifo")).code,
                          pb::dictionary_codes::kFileMissing));
    }

    // ── 11. 缓存：SaveCache → LoadCache ──────────────────────────────
    {
        const fs::path dir = MakeScratch("cache");
        const fs::path cachePath = dir / "ecdict.pbdict";
        DictionaryService source;
        CHECK_TRUE(source.ImportEcdictCsv(U8(csvPath)).ok);

        std::string error;
        CHECK_TRUE(source.SaveCache(U8(cachePath), &error));
        CHECK_TRUE(error.empty());
        CHECK_TRUE(fs::exists(cachePath));
        CHECK_EQ(source.Info().cachePath, U8(cachePath));

        DictionaryService loaded;
        CHECK_TRUE(loaded.LoadCache(U8(cachePath), &error));
        CHECK_TRUE(error.empty());
        const pb::DictionaryInfo info = loaded.Info();
        CHECK_TRUE(info.loaded);
        CHECK_EQ(info.entries, source.Size());
        CHECK_EQ(info.source, DictionarySourceKind::EcdictCsv);
        CHECK_EQ(info.cachePath, U8(cachePath));

        const pb::DictionaryLookupResult hit = loaded.Lookup("hello");
        CHECK_TRUE(hit.found);
        if (!hit.hits.empty()) {
            CHECK_TRUE(Contains(hit.hits[0].entry.translation, "你好"));
            CHECK_TRUE(Contains(hit.hits[0].entry.phonetic, "həˈləʊ"));
        }
        CHECK_TRUE(loaded.Lookup("going").found);       // 缓存里词形还原照样能用
        CHECK_TRUE(HasHit(loaded.Lookup("went"), "go", DictionaryMatchKind::Morphology));

        // 缓存可以被再次保存（往返稳定）
        const fs::path secondPath = dir / "ecdict2.pbdict";
        CHECK_TRUE(loaded.SaveCache(U8(secondPath), &error));
        DictionaryService reloaded;
        CHECK_TRUE(reloaded.LoadCache(U8(secondPath), &error));
        CHECK_EQ(reloaded.Size(), source.Size());

        // 缓存写是「临时文件 + MoveFileEx」，不该留下 .pb-tmp-* 残留
        int leftovers = 0;
        std::error_code ec;
        for (const auto& entry : fs::directory_iterator(dir, ec)) {
            if (entry.path().filename().u8string().find(".pb-tmp-") != std::string::npos) {
                ++leftovers;
            }
        }
        CHECK_EQ(leftovers, 0);
    }

    // ── 12. 缓存：不存在 / 损坏 / magic 不对 ─────────────────────────
    {
        const fs::path dir = MakeScratch("cache-bad");
        DictionaryService dict2;
        std::string error;
        CHECK_TRUE(!dict2.LoadCache(U8(dir / "absent.pbdict"), &error));
        CHECK_TRUE(CodeIs(error, pb::dictionary_codes::kCacheMissing));

        WriteText(dir / "garbage.pbdict", "not a dictionary cache at all");
        CHECK_TRUE(!dict2.LoadCache(U8(dir / "garbage.pbdict"), &error));
        CHECK_TRUE(CodeIs(error, pb::dictionary_codes::kCacheCorrupt));

        // 合法缓存被截断
        const fs::path goodPath = dir / "good.pbdict";
        DictionaryService source;
        CHECK_TRUE(source.ImportEcdictCsv(U8(csvPath)).ok);
        CHECK_TRUE(source.SaveCache(U8(goodPath), &error));
        std::string bytes = ReadText(goodPath);
        CHECK_TRUE(bytes.size() > 40);
        bytes.resize(bytes.size() - 5);
        WriteText(dir / "truncated.pbdict", bytes);
        CHECK_TRUE(!dict2.LoadCache(U8(dir / "truncated.pbdict"), &error));
        CHECK_TRUE(CodeIs(error, pb::dictionary_codes::kCacheCorrupt));

        // magic 被改
        std::string brokenMagic = ReadText(goodPath);
        CHECK_TRUE(brokenMagic.size() > 8);
        brokenMagic[2] = 'X';
        WriteText(dir / "magic.pbdict", brokenMagic);
        CHECK_TRUE(!dict2.LoadCache(U8(dir / "magic.pbdict"), &error));
        CHECK_TRUE(CodeIs(error, pb::dictionary_codes::kCacheCorrupt));
    }

    // ── 13. 大小写敏感：同一个词的大写 / 小写词条都要能精确取到 ──────
    {
        const fs::path dir = MakeScratch("case");
        const fs::path path = dir / "case.csv";
        WriteText(path,
                  "word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,"
                  "exchange,detail,audio\n"
                  "Hello,,,打招呼,n,,,,,,,\n"
                  "hello,,,你好,n,,,,,,,\n");
        DictionaryService caseDict;
        CHECK_TRUE(caseDict.ImportEcdictCsv(U8(path)).ok);

        // 默认大小写不敏感：两条都能匹配
        const pb::DictionaryLookupResult both = caseDict.Lookup("hElLo");
        CHECK_TRUE(both.found);
        CHECK_EQ(both.hits.size(), static_cast<std::size_t>(2));

        DictionaryLookupOptions sensitive;
        sensitive.caseInsensitive = false;
        const pb::DictionaryLookupResult lower = caseDict.Lookup("hello", sensitive);
        CHECK_TRUE(lower.found);
        CHECK_EQ(lower.hits.size(), static_cast<std::size_t>(1));
        if (!lower.hits.empty()) {
            CHECK_TRUE(Contains(lower.hits[0].entry.translation, "你好"));
        }
        const pb::DictionaryLookupResult upper = caseDict.Lookup("Hello", sensitive);
        CHECK_TRUE(upper.found);
        CHECK_EQ(upper.hits.size(), static_cast<std::size_t>(1));
        if (!upper.hits.empty()) {
            CHECK_TRUE(Contains(upper.hits[0].entry.translation, "打招呼"));
        }
    }

    // ── 14. StarDict：idxoffsetbits=64（8 字节偏移）──────────────────
    {
        const fs::path dir = MakeScratch("stardict-64");
        StarDictSpec spec;
        spec.base = "wide";
        spec.sameTypeSequence = "m";
        spec.idxOffset64 = true;
        spec.entries.push_back({"wide", "宽"});
        CHECK_TRUE(WriteStarDict(dir, spec));

        DictionaryService star;
        CHECK_TRUE(star.ImportStarDict(U8(dir / "wide.ifo")).ok);
        const pb::DictionaryLookupResult hit = star.Lookup("wide");
        CHECK_TRUE(hit.found);
        if (!hit.hits.empty()) {
            CHECK_TRUE(Contains(hit.hits[0].entry.translation, "宽"));
        }
    }

    std::error_code cleanupError;
    fs::remove_all(fs::temp_directory_path() / "pb_dict_test", cleanupError);
    return pb_t::Summary("dictionary_service_test");
}
