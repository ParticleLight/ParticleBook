#pragma once
// ── PB-011：BackupService —— 本地 zip 备份 / 恢复 ─────────────────────
//
// 目标（docs/BACKLOG.md PB-011）：防磁盘故障 / 重装丢数据。MVP 只做「本地
// zip 快照 + 覆盖式恢复」；WebDAV 自动备份、合并恢复、多端同步都是 Phase 2。
//
// 边界（只碰数据文件，不碰阅读器缓存的二进制）：
//   * 打包内容：DatabaseService 的 reader.json（书库 / 进度 / 标注 / 笔记 /
//     书架 / 书源 / settings 全在里面），外加一个 manifest.json。
//   * 不打包：正文缓存 _pb_files/、PDF 渲染缓存、SecretStore 的密钥文件
//     （DPAPI 密文与本机用户绑定，换机必失效；且 settings 里本来就不存 Key，
//     见 platform/SettingsService.h 的边界说明）。
//   * 不读写 SecretStore / WebView2 / BridgeServer，不依赖 GUI 与消息循环。
//
// zip 实现：写作端用 miniz 的 tdefl（raw deflate，third_party/miniz_tdef.c 已被
// 主 target 编译），读取端复用 src/utils/ZipReader.cpp（EPUB 已在用的同一套
// 解析）。刻意不引入 miniz_zip.c —— 主 target 没有编译它，本服务不能要求改
// CMake 才能用。因此备份 zip 不支持 ZIP64 / 加密 / 分卷：payload > 4 GiB 直接
// 拒绝（db_too_large），压缩后没变小的条目改 store。
//
// 完整性：manifest.sha256 = reader.json 明文的 SHA-256（BCrypt，与
// FileHandlers.cpp 的更新包校验同款）。恢复时先校验 sha256 再动目标文件；
// manifest 自身损坏 / 字段缺失一律拒绝。sha256 只能证明「包内数据和清单一致」，
// 不是防篡改签名（没有密钥），目标是防止半截写入 / 损坏 / 误选文件。
//
// 恢复的安全顺序（任何一步失败都不动现有数据库）：
//   1. 读 manifest → 2. 校验 format / 字段 → 3. 拒绝 formatVersion > 当前
//   → 4. 拒绝 schemaVersion > 当前（降级保护，与 PB-014 同款语义）
//   → 5. 取出 payload 并校验 sha256 / JSON 结构
//   → 6. 把当前 reader.json 复制成 <stem>.pre-restore-<时间>.json
//   → 7. 同目录临时文件 + MoveFileEx(REPLACE_EXISTING) 原子替换
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
//                                             bo);
//   if (!br.ok) { /* bi.code / br.error 回前端 */ }
//   // 2) 恢复：FlushSync 后关掉 DB（恢复期间不能再有内存里的旧数据覆盖回去），
//   //    恢复成功后重新 Load / 重启，并提示 preRestoreBackupPath 的位置
//   auto rr = pb::BackupService::RestoreBackup(zipPath, dbPath);
//   if (!rr.ok) { /* rr.code == schema_too_new 时要提示用户升级应用 */ }
//
// 错误码（稳定字符串，便于桥接层直接回前端 / 写日志）见 backup_codes 命名空间。
// 返回结构里的 code 在 ok=true 时为 "ok"。

#include "nlohmann/json.hpp"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace pb {

// 当前备份包格式版本。manifest 字段形状一变就要 +1（读取端兼容旧版本）。
inline constexpr int kBackupFormatVersion = 1;

// 固定的 zip 条目名（不要随源文件名变，恢复端只认这两个）。
inline constexpr const char* kBackupManifestEntry = "manifest.json";
inline constexpr const char* kBackupPayloadEntry  = "reader.json";

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
inline constexpr const char* kPayloadMissing   = "payload_missing";    // 清单在但 reader.json 取不出
inline constexpr const char* kPayloadInvalid   = "payload_invalid";    // payload 不是合法 JSON 对象
inline constexpr const char* kChecksumMismatch = "checksum_mismatch";  // sha256 与清单不符
inline constexpr const char* kSchemaTooNew     = "schema_too_new";     // 库版本比本应用新（降级保护）
inline constexpr const char* kPreRestoreFailed = "pre_restore_failed"; // 恢复前备份旧库失败
inline constexpr const char* kWriteFailed      = "write_failed";       // 原子替换目标数据库失败
} // namespace backup_codes

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

    nlohmann::json ToJson() const;
    // 严格解析：format / formatVersion / schemaVersion / createdAt / bookCount /
    // sha256 缺一不可；失败时 error 写入原因（英文，可直接进日志）。
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
};

struct BackupResult {
    bool           ok = false;
    std::string    code;                      // 成功 "ok"，失败见 backup_codes
    std::string    error;                     // 人类可读原因（英文，进日志 / 提示）
    std::string    outputPath;                // 实际写出的 zip 路径
    BackupManifest manifest;
    std::uint64_t  archiveBytes = 0;          // zip 文件字节数
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
    static BackupResult CreateBackup(const std::string& dbPath,
                                     const std::string& outputZipPath,
                                     const BackupOptions& options = BackupOptions());

    // 覆盖式恢复：把 zipPath 里的 reader.json 写回 targetDbPath。
    // 失败时 targetDbPath 保持原样（检查全部在写入之前，写入是原子替换）。
    // 成功后调用方必须重新 Load / 重启，别继续用内存里的旧库。
    static RestoreResult RestoreBackup(const std::string& zipPath,
                                       const std::string& targetDbPath,
                                       const RestoreOptions& options = RestoreOptions());

    // 只读清单（不读 payload，不校验 sha256 —— 那是 RestoreBackup 的活）。
    // 失败返回 false，errorCode 填入 backup_missing / manifest_missing /
    // manifest_invalid / format_too_new。
    static bool InspectBackup(const std::string& zipPath,
                              BackupManifest& out,
                              std::string* errorCode = nullptr);

    // 列目录下的 *.zip 备份，按修改时间倒序。目录不存在 / 无权限返回空。
    static std::vector<BackupFileInfo> ListBackups(const std::string& directory);

    // SHA-256 → 64 位小写 hex。空输入返回空串哈希（e3b0c442…）。
    static std::string Sha256Hex(const void* data, std::size_t len);
    static std::string Sha256Hex(const std::string& data);

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
