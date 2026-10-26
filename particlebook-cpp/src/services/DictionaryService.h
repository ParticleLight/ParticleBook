#pragma once
// ── PB-010：DictionaryService —— 离线本地词典（ECDICT / StarDict）──────
//
// 目标（docs/BACKLOG.md PB-010）：离线查词，不依赖 AI / 网络。选中单词弹层要
// 有音标 / 释义 / 词性 / 词形 / 考试标签；英文要按 lemma 还原词形。
//
// 支持两种数据源（词库文件由用户自备，**不随仓库分发**）：
//   * ECDICT 的 stardict.csv（MIT）：word,phonetic,definition,translation,pos,
//     collins,oxford,tag,bnc,frq,exchange,detail,audio。本服务只留
//     word / phonetic / definition / translation / pos / tag / exchange
//     （collins/oxford/bnc/frq/detail/audio 丢弃，省内存；collins 等以后要用
//     再进列）。
//   * StarDict（.ifo + .idx + .dict，idx/dict 允许 gzip / dictzip 压缩）：
//     sametypesequence 与逐字段类型前缀两种数据布局都支持；文字类型
//     m/t/y/x/k/h/g/w/n 取文本（标记类会剥标签），二进制类型 W/P 的条目整体跳过。
//
// 内存布局（这是本文件最需要小心的地方）：
//   所有词条字段顺序拼在一个连续 blob 里，每条记录 = 8 个 u32 长度 + 各字段字节；
//   另存一个按「小写 word」排序的 u32 偏移数组。查词 = 排序数组上二分，
//   不复制大对象。770k 词条的 ECDICT 约占 150 MB blob + 3 MB 偏移，
//   导入时可用 DictionaryImportOptions 的条目数 / 字节数上限兜底。
//
// 落盘缓存（可选，强烈建议）：导入一次 ECDICT 要解析几十万行 CSV，慢；
// SaveCache 把「已排序的记录」写成二进制缓存，LoadCache 只做一遍扫描 + 校验，
// 启动时直接加载。缓存格式带 magic/version/来源/条目数/创建时间，坏文件一律拒绝。
//
// 查词顺序（Lookup）：
//   1. 精确（默认大小写不敏感，用 ASCII 小写比较）
//   2. 可选前缀（options.prefix，精确没命中才扫；有扫描上限）
//   3. 形态还原（options.morphology）：
//      a) 精确命中的词条 exchange 里有 "0:lemma" → 附带原形词条；
//      b) 没命中 → 按后缀规则猜原形（-s/-es/-ies/-ed/-ied/-ing/-er/-est/-ly、
//         双写辅音、去 e 等），找到后用原形的 exchange 字段反向验证
//         （例如 go 的 exchange 含 goes/going → 查 goes 命中 go 且 verified=true）。
//   缺失返回 ok=true / found=false / code="not_found"（不是错误）。
//
// 线程安全：实例方法可跨线程调用；导入 / 加载在内部构建完成后一次性换入，
// 失败或取消不会破坏已加载的词典。导入可在任意线程跑（不碰 GUI）。
//
// 边界：只读用户本地文件、不联网、不解析 MDict(.mdx)；中文分词不在此处
// （PB-001/PB-002 的取词层负责把词交给 Lookup）。
//
// 接线（handler / App 侧，本轮不改 App / CMake / Bridge / renderer）：
//   pb::DictionaryService dict;
//   std::string err;
//   if (!dict.LoadCache(cachePath, &err)) {          // 启动：优先缓存
//       pb::DictionaryImportOptions io;
//       io.cachePath = cachePath;                     // 导入成功后顺手写缓存
//       pb::DictionaryImportResult r = dict.ImportEcdictCsv(ecdictCsv, io);
//   }
//   auto hit = dict.Lookup("went");                   // ok=true, found=true
//
// 错误码（稳定字符串）见 dictionary_codes。

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace pb {

// 稳定错误码。导入 / 加载 / 查询结果的 code 字段用它。
namespace dictionary_codes {
inline constexpr const char* kOk            = "ok";
inline constexpr const char* kNotFound      = "not_found";
inline constexpr const char* kNotLoaded     = "not_loaded";
inline constexpr const char* kEmptyQuery    = "empty_query";
inline constexpr const char* kQueryTooLong  = "query_too_long";
inline constexpr const char* kCancelled     = "cancelled";
inline constexpr const char* kFileMissing   = "file_missing";
inline constexpr const char* kFileUnreadable = "file_unreadable";
inline constexpr const char* kTooLarge      = "too_large";
inline constexpr const char* kEmpty         = "empty";        // 文件里没有一个可用词条
inline constexpr const char* kBadCsv        = "bad_csv";
inline constexpr const char* kBadIfo        = "bad_ifo";
inline constexpr const char* kBadIdx        = "bad_idx";
inline constexpr const char* kBadDict       = "bad_dict";
inline constexpr const char* kBadGzip       = "bad_gzip";
inline constexpr const char* kIoError       = "io_error";
inline constexpr const char* kCacheMissing  = "cache_missing";
inline constexpr const char* kCacheCorrupt  = "cache_corrupt";
inline constexpr const char* kCacheVersion  = "cache_version";
} // namespace dictionary_codes

// 词典来源（写进缓存，Info() 里给 UI 显示）。
enum class DictionarySourceKind : std::uint32_t {
    None = 0,
    EcdictCsv = 1,
    StarDict = 2,
};

// 一条词条（查询结果里是拷贝，出锁后仍可用）。
struct DictionaryEntry {
    std::string word;         // 词头（原样，保留大小写）
    std::string phonetic;     // 音标
    std::string translation;  // 中文释义（ECDICT translation / StarDict 中文文本）
    std::string definition;   // 英文释义（ECDICT definition / StarDict 英文文本）
    std::string pos;          // 词性（ECDICT pos，如 "n:68/v:32"）
    std::string tag;          // 考试标签（ECDICT tag，如 "cet4 cet6 ky"）
    std::string exchange;     // 词形变化（ECDICT exchange，如 "p:went/d:gone/0:go"）
    std::string example;      // 例句（当前两种来源都没有，预留）
};

// 这条命中是怎么来的。
enum class DictionaryMatchKind {
    Exact = 0,          // 精确（可能大小写不同）
    Prefix = 1,         // 前缀
    Morphology = 2,     // 词形还原（exchange / 后缀规则）
};

// 单条命中。
struct DictionaryHit {
    DictionaryEntry entry;
    DictionaryMatchKind kind = DictionaryMatchKind::Exact;
    std::string matched;     // 词典里真正命中的键（morphology 时是还原出的原形）
    bool verified = false;   // morphology：原形的 exchange 字段里能反查到本词
};

struct DictionaryLookupOptions {
    bool        prefix = false;          // 精确没命中时再按前缀找
    bool        caseInsensitive = true;  // ASCII 大小写不敏感
    bool        morphology = true;       // exchange / 后缀词形还原
    std::size_t maxResults = 10;         // 返回上限（前缀扫描也受它约束）
    std::size_t maxPrefixScan = 2000;    // 前缀扫描最多看多少条（防病态前缀）
};

struct DictionaryLookupResult {
    bool        ok = false;      // 查询本身是否有效（"没这个词" 时 ok=true）
    bool        found = false;   // hits 非空
    std::string code;            // 见 dictionary_codes
    std::string error;           // 人类可读（英文，进日志）
    std::string query;           // 归一化后的输入（trim）
    std::vector<DictionaryHit> hits;
};

// 导入进度（回调在导入线程同步调用，不要在里面做重活）。
struct DictionaryProgress {
    std::int64_t processed = 0;       // 已处理行 / 词条
    std::int64_t bytesProcessed = 0;
    std::int64_t bytesTotal = 0;      // 0 = 未知
    std::string  stage;               // "reading" / "parsing" / "indexing" / "saving"
};

using DictionaryProgressFn = std::function<void(const DictionaryProgress&)>;

struct DictionaryImportOptions {
    // 内存兜底：超过任一上限就放弃本次导入（已加载的词典保持不变）。
    std::size_t maxEntries = 1500000;
    std::size_t maxBytes = 512ull * 1024ull * 1024ull;      // 记录总字节
    std::size_t maxFileBytes = 256ull * 1024ull * 1024ull;  // 单个源文件读取上限
    std::size_t maxWordBytes = 256;                         // 单个词头长度上限
    // 非空：导入成功后把索引写成缓存（失败不影响导入结果，见 cacheSaved）。
    std::string cachePath;
};

struct DictionaryImportResult {
    bool               ok = false;
    std::string        code;
    std::string        error;
    DictionarySourceKind source = DictionarySourceKind::None;
    std::size_t        entries = 0;      // 实际索引的条目数
    std::size_t        bytes = 0;        // blob 字节数
    std::size_t        skipped = 0;      // 跳过的行 / 条目（空行、坏条目、二进制字段）
    std::int64_t       elapsedMs = 0;
    bool               cacheSaved = false;
    std::string        cachePath;
    std::string        cacheError;       // cacheSaved=false 时的原因
};

struct DictionaryInfo {
    bool                loaded = false;
    DictionarySourceKind source = DictionarySourceKind::None;
    std::size_t         entries = 0;
    std::size_t         bytes = 0;
    std::string         cachePath;       // 最近一次加载 / 保存的缓存路径（可空）
    std::int64_t        loadedAtUnix = 0;
};

// ── 词典服务（实例持有数据；导入后可长期复用）────────────────────────
class DictionaryService {
public:
    DictionaryService();
    ~DictionaryService();
    DictionaryService(const DictionaryService&) = delete;
    DictionaryService& operator=(const DictionaryService&) = delete;

    // ECDICT stardict.csv 导入（UTF-8，可带表头）。progress/cancel 可为空。
    // 大文件：整文件读入内存解析（受 maxFileBytes 限制），可用 progress 报进度。
    DictionaryImportResult ImportEcdictCsv(
        const std::string& csvPath,
        const DictionaryImportOptions& options = DictionaryImportOptions(),
        const DictionaryProgressFn& progress = nullptr,
        const std::atomic_bool* cancel = nullptr);

    // StarDict 导入：给 .ifo 路径，自动找同名 .idx / .idx.gz 与 .dict / .dict.dz。
    DictionaryImportResult ImportStarDict(
        const std::string& ifoPath,
        const DictionaryImportOptions& options = DictionaryImportOptions(),
        const DictionaryProgressFn& progress = nullptr,
        const std::atomic_bool* cancel = nullptr);

    // 二进制缓存（建议放在词典源文件旁边，扩展名 .pbdict）。
    bool LoadCache(const std::string& cachePath, std::string* errorCode = nullptr);
    bool SaveCache(const std::string& cachePath, std::string* errorCode = nullptr);

    // 查词。没有精确命中时按 options 决定是否前缀 / 形态还原。
    DictionaryLookupResult Lookup(const std::string& word,
                                  const DictionaryLookupOptions& options =
                                      DictionaryLookupOptions()) const;

    // 前缀补全（只看词头，最多 maxResults 条）。
    std::vector<std::string> Suggest(const std::string& prefix,
                                     std::size_t maxResults = 10) const;

    void Clear();
    bool Empty() const;
    std::size_t Size() const;
    DictionaryInfo Info() const;

    // ECDICT exchange 解析："p:went/d:gone/0:go" → {("p","went"),("d","gone"),("0","go")}。
    // 公开出来是为了渲染层能直接显示「过去式 / 过去分词 / 原型」。
    static std::vector<std::pair<std::string, std::string>>
    ParseExchange(const std::string& exchange);

    // 匹配类型 → 稳定字符串（"exact" / "prefix" / "morphology"）。
    static const char* MatchKindName(DictionaryMatchKind kind);

private:
    mutable std::mutex m_mutex;
    std::string m_blob;                 // 所有记录连续存放
    std::vector<std::uint32_t> m_offsets; // 按小写 word 排序的记录偏移
    DictionarySourceKind m_source = DictionarySourceKind::None;
    std::string m_cachePath;
    std::int64_t m_loadedAtUnix = 0;
};

} // namespace pb
