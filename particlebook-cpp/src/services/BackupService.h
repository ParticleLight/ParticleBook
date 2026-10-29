#pragma once
// ── PB-011：BackupService —— 本地 zip 备份 / 恢复 ─────────────────────
//
// 目标（docs/BACKLOG.md PB-011）：防磁盘故障 / 重装丢数据。MVP 只做「本地
// zip 快照 + 覆盖式恢复」；WebDAV 自动备份、合并恢复、多端同步都是 Phase 2。
//
// 边界（只碰数据文件 + 封面图片）：
//   * 打包内容：DatabaseService 的 reader.json（书库 / 进度 / 标注 / 笔记 /
//     书架 / 书源 / settings 全在里面）、manifest.json，以及可选的书籍封面
//     文件（books[].cover_path，PB-011-B）。
//   * 不打包：正文缓存 _pb_files/、PDF 渲染缓存、SecretStore 的密钥文件
//     （DPAPI 密文与本机用户绑定，换机必失效；且 settings 里本来就不存 Key，
//     见 platform/SettingsService.h 的边界说明）。
//   * 不读写 SecretStore / WebView2 / BridgeServer，不依赖 GUI 与消息循环。
//
// 格式版本（manifest.formatVersion）：
//   v1 —— manifest.json + reader.json（没有封面）。
//   v2 —— 增加可选的 covers[]：每条记录 entry（zip 内 covers/…）、sourcePath
//         （备份时 books[].cover_path 原值）、sha256、bytes。**读取端兼容 v1**：
//         没有 covers 字段就当作没有封面；写入端一律写 v2。
//
// zip 实现：写作端用 miniz 的 tdefl（raw deflate，third_party/miniz_tdef.c 已被
// 主 target 编译），读取端复用 src/utils/ZipReader.cpp（EPUB 已在用的同一套
// 解析）。刻意不引入 miniz_zip.c —— 主 target 没有编译它，本服务不能要求改
// CMake 才能用。因此备份 zip 不支持 ZIP64 / 加密 / 分卷：payload > 4 GiB 直接
// 拒绝（db_too_large），压缩后没变小的条目改 store。
//
// 完整性：manifest.sha256 = reader.json 明文的 SHA-256（BCrypt，与
// FileHandlers.cpp 的更新包校验同款）；covers[].sha256 = 封面文件明文的 SHA-256。
// 恢复时先校验 sha256 再动目标文件；manifest 自身损坏 / 字段缺失一律拒绝。
// sha256 只能证明「包内数据和清单一致」，不是防篡改签名（没有密钥），目标是
// 防止半截写入 / 损坏 / 误选文件。
//
// 恢复的安全顺序（任何一步失败都不动现有数据库）：
//   1. 读 manifest → 2. 校验 format / 字段 / 封面条目路径（拒绝穿越）
//   → 3. 拒绝 formatVersion > 当前 → 4. 拒绝 schemaVersion > 当前（PB-014 同款）
//   → 5. 取出 payload 并校验 sha256 / JSON 结构
//   → 6. 逐张恢复封面：先试写回 sourcePath（仅当路径安全且可写），失败则写到
//      <数据库目录>\covers\<安全文件名>；封面失败只记 issue，不阻塞整库恢复
//   → 7. 有封面被搬走时回填 books[].cover_path（重新序列化 payload）
//   → 8. 把当前 reader.json 复制成 <stem>.pre-restore-<时间>.json
//   → 9. 同目录临时文件 + MoveFileEx(REPLACE_EXISTING) 原子替换
//
// 进度 / 取消（PB-011-B）：CreateBackup / RestoreBackup 末尾多了两个可选参数
// （progress 回调 + std::atomic_bool* cancel），旧调用不用改。大库分阶段报告：
//   CreateBackup: reading_db → reading_covers → writing_archive → done
//   RestoreBackup: inspecting → extracting → restoring_covers → backup_current
//                  → writing_db → done
// 取消返回 code="cancelled"：备份不产出 zip，恢复不动数据库（已写出的封面文件
// 可能留下孤儿文件，不影响数据）。
//
// 错误处理：封面缺失 / 不可读 / 校验不符只在结果 issues 里记一条，不阻塞备份或
// 整库恢复；zip 内路径穿越（covers/../x、绝对路径、反斜杠、盘符、. / .. 段）
// 在恢复前直接拒绝（code="path_traversal"），一个字节都不写。
//
// 线程安全：全部静态函数、无全局可变状态（只有函数内数据），任意线程可并发调用；
// 但同一个目标文件的写是「最后到者赢」，调用方需自己串行化（恢复尤其要独占）。
// 路径：C++ 侧一律 UTF-8 std::string（与 ZipReader 一致），内部转宽字符调 Win32。
//
// 接线（App / handler 侧，本轮不改 App / CMake / DB / Bridge）：
//   DatabaseService db;
//   db.Load(dbPath);
//   // 1) 备份：先把 300ms 去抖的写落盘，否则快照可能少最后一次修改
//   db.FlushSync();
//   pb::BackupOptions bo;
//   bo.appVersion = PB_VERSION_STRING;
//   auto br = pb::BackupService::CreateBackup(dbPath,
//                                             pb::BackupService::DefaultBackupFileName(),
//                                             bo,
//                                             [](const pb::BackupProgress& p) { /* UI */ },
//                                             &cancelFlag);
//   if (!br.ok) { /* bi.code / br.error 回前端 */ }
//   // 2) 恢复：FlushSync 后关掉 DB（恢复期间不能再有内存里的旧数据覆盖回去），
//   //    恢复成功后重新 Load / 重启，并提示 preRestoreBackupPath 的位置
//   auto rr = pb::BackupService::RestoreBackup(zipPath, dbPath);
//   if (!rr.ok) { /* rr.code == schema_too_new 时要提示用户升级应用 */ }
//
// 错误码（稳定字符串，便于桥接层直接回前端 / 写日志）见 backup_codes 命名空间。
// 返回结构里的 code 在 ok=true 时为 "ok"。

#include "nlohmann/json.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace pb {

// 当前备份包格式版本。manifest 字段形状一变就要 +1（读取端兼容旧版本）。
// v2 = 增加可选 covers[]（PB-011-B）；v1 备份仍可读。
inline constexpr int kBackupFormatVersion = 2;

// 固定的 zip 条目名（不要随源文件名变，恢复端只认这些前缀）。
inline constexpr const char* kBackupManifestEntry = "manifest.json";
inline constexpr const char* kBackupPayloadEntry  = "reader.json";
inline constexpr const char* kBackupCoverPrefix   = "covers/";

// 稳定错误码。ok=true 时 code == kOk。
namespace backup_codes {
inline constexpr const char* kOk               = "ok";
inline constexpr const char* kDbMissing        = "db_missing";         // 源数据库文件不存在
inline constexpr const char* kDbUnreadable     = "db_unreadable";      // 打开 / 读取失败
inline constexpr const char* kDbEmpty          = "db_empty";           // 0 字节
inline constexpr const char* kDbTooLarge       = "db_too_large";       // > 4 GiB，ZIP32 装不下
inline constexpr const char* kInvalidDb        = "invalid_db";         // 不是合法 JSON 对象
inline constexpr const char* kInvalidOptions   = "invalid_options";    // 输出路径 / createdAt 非法
inline constexpr const char* kIoError          = "io_error";           // 目录不存在 / 写入失败
inline constexpr const char* kTooLarge         = "too_large";          // 打包后超过 ZIP32 上限
inline constexpr const char* kBackupMissing    = "backup_missing";     // 备份 zip 不存在
inline constexpr const char* kManifestMissing  = "manifest_missing";   // 不是本应用的备份 / zip 损坏
inline constexpr const char* kManifestInvalid  = "manifest_invalid";   // manifest 字段缺失 / 类型错
inline constexpr const char* kFormatTooNew     = "format_too_new";     // 备份格式比本版本新
inline constexpr const char* kPathTraversal    = "path_traversal";     // zip 内封面条目路径穿越
inline constexpr const char* kPayloadMissing   = "payload_missing";    // 清单在但 reader.json 取不出
inline constexpr const char* kPayloadInvalid   = "payload_invalid";    // payload 不是合法 JSON 对象
inline constexpr const char* kChecksumMismatch = "checksum_mismatch";  // sha256 与清单不符
inline constexpr const char* kSchemaTooNew     = "schema_too_new";     // 库版本比本应用新（降级保护）
inline constexpr const char* kPreRestoreFailed = "pre_restore_failed"; // 恢复前备份旧库失败
inline constexpr const char* kWriteFailed      = "write_failed";       // 原子替换目标数据库失败
inline constexpr const char* kCancelled        = "cancelled";          // 进度回调之外被取消
} // namespace backup_codes

// ── 一张封面的清单项（covers[] 的元素）──────────────────────────────
struct BackupCoverEntry {
    std::string entry;        // zip 内条目名：covers/<序号>-<安全文件名>
    std::string sourcePath;   // 备份时 books[].cover_path 原值（可空：旧包 / 手写包）
    std::string sha256;       // 封面明文 SHA-256（64 位小写 hex）
    std::uint64_t bytes = 0;  // 封面明文大小

    nlohmann::json ToJson() const;
    static bool FromJson(const nlohmann::json& j, BackupCoverEntry& out, std::string* error);
};

// ── manifest.json 的内容 ────────────────────────────────────────────
struct BackupManifest {
    std::string format = "particlebook-backup";
    int         formatVersion = kBackupFormatVersion;
    std::string appVersion = "unknown";   // 生成备份的应用版本（PB_VERSION_STRING）
    int         schemaVersion = 0;        // 备份时 reader.json 的 schema_version
    std::string createdAt;                // UTC ISO-8601："YYYY-MM-DDTHH:MM:SSZ"
    int         bookCount = 0;            // books 数组长度（0 = 空库 / 未解析）
    std::string sourceFileName = "reader.json"; // 仅记录来源文件名，恢复端不用它做路径
    std::uint64_t dbBytes = 0;            // reader.json 明文字节数
    std::string sha256;                   // reader.json 明文的 SHA-256，64 位小写 hex
    std::vector<BackupCoverEntry> covers; // v2：可选的封面列表（v1 包为空）

    nlohmann::json ToJson() const;
    // 严格解析：format / formatVersion / schemaVersion / createdAt / bookCount /
    // sha256 缺一不可；covers 可有可无（缺失 = 无封面）。失败时 error 写原因。
    static bool FromJson(const nlohmann::json& j, BackupManifest& out, std::string* error);
};

// ── CreateBackup 选项 ──────────────────────────────────────────────
struct BackupOptions {
    // 生成备份的应用版本；App 侧传 PB_VERSION_STRING，测试传固定值。
    std::string appVersion = "unknown";
    // >= 0 时强制写入清单；< 0（默认）从 reader.json 的 schema_version 推断，
    // 缺省当 1（PB-014：v1 = 未版本化的旧库）。
    int schemaVersionOverride = -1;
    // 非空时作为 createdAt（UTC ISO-8601）并同时用作 zip 条目时间；
    // 测试用固定值让产物可复现。空 = 当前时间。
    std::string createdAt;
    // 打包前把 reader.json 解析成 JSON 对象，坏库 / 半截写拒绝备份（默认开）。
    bool validateDb = true;
    // 用 raw deflate 压缩 payload；压缩后没变小则自动退回 store（默认开）。
    bool compress = true;
    // PB-011-B：把 books[].cover_path 指向的封面文件一起打包（默认开）。
    // 缺失 / 不可读 / 超限只记 issues，不影响备份成功。
    bool includeCovers = true;
    // 封面数量上限（防病态库把备份撑爆）；超过的部分只记 issue。
    std::size_t maxCovers = 2000;
    // 单张封面明文字节上限；超过则跳过并记 issue。
    std::size_t maxCoverBytes = 32ull * 1024ull * 1024ull;
};

// ── RestoreBackup 选项 ─────────────────────────────────────────────
struct RestoreOptions {
    // 本应用支持的 schema 版本，> 该值的备份拒绝恢复（降级保护）。
    // < 0（默认）= DatabaseService::kCurrentSchemaVersion。
    int currentSchemaVersion = -1;
    // 恢复前把当前 reader.json 复制一份（默认开；失败则中止恢复）。
    bool makePreRestoreBackup = true;
    // 校验 payload 的 sha256（默认开；关掉只适合排查损坏包，不建议）。
    bool verifyChecksum = true;
    // 恢复前把 payload 解析成 JSON 对象，避免把垃圾写成数据库（默认开）。
    bool validatePayload = true;
    // PB-011-B：是否恢复封面（默认开）。关掉只恢复数据库。
    bool restoreCovers = true;
    // 是否尝试写回封面原来的绝对路径（默认**关闭**）：关掉时一律落到 coverDir。
    // 安全边界：备份包本身能决定「往哪个绝对路径写一张图片」，所以默认不写回；
    // 即使调用方显式打开，也只允许落在应用数据目录内（数据库所在目录、其上一级
    // = <UserData>、以及 coverDir），其余路径仍回落到 coverDir 并记 issue。
    // 见 BackupService.cpp 的 IsAllowedCoverRestorePath()。
    bool restoreCoversToOriginalPath = false;
    // 封面落盘目录（仅用于原路径不可写时）。空 = <数据库目录>\covers。
    std::string coverDir;
    // 封面被搬走时回填 books[].cover_path（默认开；关掉则数据库仍指向原路径）。
    bool backfillCoverPaths = true;
    // 单张封面明文字节上限（防止坏包撑内存）；超过则跳过并记 issue。
    std::size_t maxCoverBytes = 32ull * 1024ull * 1024ull;
};

// ── 进度（回调在调用线程同步执行；别在回调里做重活）──────────────────
struct BackupProgress {
    std::string  stage;              // 见文件头说明
    std::int64_t processed = 0;      // 已处理条目（封面张数等）
    std::int64_t total = 0;          // 总数（0 = 未知）
    std::int64_t bytesProcessed = 0;
    std::int64_t bytesTotal = 0;
    std::string  message;            // 可选的当前条目描述（路径等）
};

using BackupProgressFn = std::function<void(const BackupProgress&)>;

struct BackupResult {
    bool           ok = false;
    std::string    code;                      // 成功 "ok"，失败见 backup_codes
    std::string    error;                     // 人类可读原因（英文，进日志 / 提示）
    std::string    outputPath;                // 实际写出的 zip 路径
    BackupManifest manifest;
    std::uint64_t  archiveBytes = 0;          // zip 文件字节数
    int            coversIncluded = 0;        // 实际打进包的封面数
    std::vector<std::string> issues;          // 非致命问题（封面缺失 / 不可读…）
};

struct RestoreResult {
    bool           ok = false;
    std::string    code;                      // 成功 "ok"，失败见 backup_codes
    std::string    error;
    BackupManifest manifest;
    std::string    restoredPath;              // 实际写入的数据库路径
    std::string    preRestoreBackupPath;      // 空 = 没有旧库可备份 / 已关闭该选项
    std::uint64_t  restoredBytes = 0;
    int            bookCount = 0;             // 恢复后库里的 books 数量
    int            coversRestored = 0;        // 成功恢复的封面数
    int            coversRelocated = 0;       // 其中落到 coverDir（原路径不可写）的数量
    std::vector<std::string> issues;          // 非致命问题（封面缺失 / 校验不符…）
};

// ListBackups 的条目（按修改时间倒序，最新的在前）。
struct BackupFileInfo {
    std::string   path;            // 完整 UTF-8 路径
    std::string   fileName;        // 文件名（*.zip）
    std::uint64_t bytes = 0;
    std::int64_t  modifiedEpoch = 0; // Unix 秒
};

// ── 服务本体（全部静态、无状态）──────────────────────────────────────
class BackupService {
public:
    // 把 dbPath 打成 outputZipPath。只写 outputZipPath；不动源数据库。
    // 成功：ok=true，manifest 已填，archiveBytes 为产物大小。
    // 失败：ok=false，code 见 backup_codes；不会留下半截 zip（临时文件 + 原子替换）。
    // progress / cancel 可选（PB-011-B）；取消返回 code="cancelled"。
    static BackupResult CreateBackup(const std::string& dbPath,
                                     const std::string& outputZipPath,
                                     const BackupOptions& options = BackupOptions(),
                                     const BackupProgressFn& progress = nullptr,
                                     const std::atomic_bool* cancel = nullptr);

    // 覆盖式恢复：把 zipPath 里的 reader.json（+ 可选封面）写回。
    // 失败时 targetDbPath 保持原样（检查全部在写入之前，写入是原子替换）。
    // 成功后调用方必须重新 Load / 重启，别继续用内存里的旧库。
    // 封面失败不阻塞恢复（见 result.issues）；路径穿越整包拒绝。
    static RestoreResult RestoreBackup(const std::string& zipPath,
                                       const std::string& targetDbPath,
                                       const RestoreOptions& options = RestoreOptions(),
                                       const BackupProgressFn& progress = nullptr,
                                       const std::atomic_bool* cancel = nullptr);

    // 只读清单（不读 payload / 封面，不校验 sha256 —— 那是 RestoreBackup 的活）。
    // 失败返回 false，errorCode 填入 backup_missing / manifest_missing /
    // manifest_invalid / format_too_new / path_traversal。
    static bool InspectBackup(const std::string& zipPath,
                              BackupManifest& out,
                              std::string* errorCode = nullptr);

    // 列目录下的 *.zip 备份，按修改时间倒序。目录不存在 / 无权限返回空。
    static std::vector<BackupFileInfo> ListBackups(const std::string& directory);

    // SHA-256 → 64 位小写 hex。空输入返回空串哈希（e3b0c442…）。
    static std::string Sha256Hex(const void* data, std::size_t len);
    static std::string Sha256Hex(const std::string& data);

    // zip 内条目名是否安全（covers/ 下、无 .. / 绝对路径 / 反斜杠 / 盘符）。
    // 公开出来是为了让 handler / 审计工具复用同一套判定。
    static bool IsSafeCoverEntryName(const std::string& entryName);

    // 备份文件名："particlebook-backup-YYYYMMDD-HHMMSS.zip"（createdAtUtc 为空取当前 UTC）。
    static std::string DefaultBackupFileName(const std::string& createdAtUtc = std::string());

    // 恢复前备份的默认路径：同目录、<stem>.pre-restore-YYYYMMDD-HHMMSS<ext>。
    // 只算路径，不落盘；真正恢复时若已存在会自动加 -2 / -3 后缀。
    static std::string PreRestoreNameFor(const std::string& dbPath,
                                         const std::string& createdAtUtc = std::string());

    // 当前 UTC 时间，"YYYY-MM-DDTHH:MM:SSZ"。
    static std::string NowUtcIso8601();
};

} // namespace pb
