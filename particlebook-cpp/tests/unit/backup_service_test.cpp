// ── PB-011：BackupService 单元测试 ─────────────────────────────────
// 不依赖 Win32 GUI / WebView2 / BridgeServer：直接链接 BackupService.cpp +
// utils/ZipReader.cpp + third_party/miniz*.c（见 CMake 的 backup_service_test）。
//
// 覆盖（对应 docs/BACKLOG.md PB-011 MVP 验收）：
//   * 导出 zip：reader.json 进包、manifest 字段（appVersion / schemaVersion /
//     createdAt / bookCount / sha256 / dbBytes）齐全且自洽、源库不被改动、
//     目录里不留 .pb-tmp 临时文件
//   * InspectBackup：与 CreateBackup 的清单一致；不是本应用的 zip 被识别
//   * 恢复：内容逐字节一致、bookCount、恢复前自动备份存在且是「恢复前」的内容、
//     目标不存在时不产生 pre-restore；compress=store 的包同样能恢复
//   * 拒绝：损坏 zip（截断）、zip 里没有 manifest、manifest sha256 被篡改
//     （checksum_mismatch）、schema 比本应用新（schema_too_new）——
//     且被拒绝时目标数据库一个字节都没动
//   * 入口参数：库文件不存在 / 空文件 / 非 JSON 对象 / 空路径
//   * 文件名工具：DefaultBackupFileName / PreRestoreNameFor / NowUtcIso8601
//   * SHA-256 已知向量（BCrypt 实现正确性）
//
// 每个用例用自己的临时目录，绝不碰真实的 %APPDATA% 数据库。

#include "services/BackupService.h"
#include "utils/test_assert.h"

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <system_error>
#include <vector>

namespace fs = std::filesystem;
using pb::BackupManifest;
using pb::BackupOptions;
using pb::BackupService;
using pb::RestoreOptions;

namespace {

fs::path MakeScratch(const char* name)
{
    const fs::path dir = fs::temp_directory_path() / "pb_backup_test" / name;
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir, ec);
    return dir;
}

std::string U8(const fs::path& path)
{
    return path.u8string();
}

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

bool FileSizeIs(const fs::path& path, std::uint64_t expected)
{
    std::error_code ec;
    const std::uintmax_t size = fs::file_size(path, ec);
    return !ec && static_cast<std::uint64_t>(size) == expected;
}

int CountFilesWithPrefix(const fs::path& dir, const std::string& prefix)
{
    int count = 0;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(dir, ec)) {
        const std::string name = entry.path().filename().u8string();
        if (name.rfind(prefix, 0) == 0) ++count;
    }
    return count;
}

int CountFilesContaining(const fs::path& dir, const std::string& needle)
{
    int count = 0;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(dir, ec)) {
        const std::string name = entry.path().filename().u8string();
        if (name.find(needle) != std::string::npos) ++count;
    }
    return count;
}

bool CodeIs(const std::string& code, const char* expected)
{
    return code == expected;
}

// 合法 reader.json 形状的小库（DatabaseService 的顶层键）。
std::string MakeDatabase(int schemaVersion, int bookCount)
{
    std::string text = "{\"schema_version\":" + std::to_string(schemaVersion) + ",\"books\":[";
    for (int i = 0; i < bookCount; ++i) {
        if (i != 0) text += ",";
        text += "{\"id\":" + std::to_string(i + 1) +
                ",\"title\":\"Book " + std::to_string(i + 1) + "\"}";
    }
    text += "],\"settings\":{\"theme\":\"dark\"},\"notes\":[{\"id\":1,\"content\":\"n\"}]}";
    return text;
}

// 只有一个 EOCD 的空 zip（0 个条目）——「是 zip，但不是本应用的备份」。
std::string EmptyZip()
{
    std::string zip;
    const unsigned char bytes[] = {
        0x50, 0x4B, 0x05, 0x06,  // EOCD signature
        0x00, 0x00,              // disk number
        0x00, 0x00,              // disk with central directory
        0x00, 0x00,              // entries on this disk
        0x00, 0x00,              // total entries
        0x00, 0x00, 0x00, 0x00,  // central directory size
        0x00, 0x00, 0x00, 0x00,  // central directory offset
        0x00, 0x00               // comment length
    };
    zip.assign(reinterpret_cast<const char*>(bytes), sizeof bytes);
    return zip;
}

} // namespace

int main()
{
    // ── 1. SHA-256 已知向量（BCrypt 包装正确性）───────────────────────
    {
        CHECK_EQ(BackupService::Sha256Hex(std::string()),
                 std::string("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
        CHECK_EQ(BackupService::Sha256Hex(std::string("abc")),
                 std::string("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        // 大一点、跨内部 chunk 边界的输入：256 KB 的 'a'，长度与字符集都自检。
        const std::string big(256 * 1024, 'a');
        const std::string digest = BackupService::Sha256Hex(big);
        CHECK_EQ(digest.size(), static_cast<std::size_t>(64));
        for (char c : digest) {
            CHECK_TRUE((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
        }
    }

    // ── 2. 创建备份：文件、清单字段、源库不变、无临时文件残留 ─────────
    {
        const fs::path dir = MakeScratch("create");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "particlebook-backup-20261026-100000.zip";
        const std::string dbText = MakeDatabase(2, 2);
        WriteText(dbPath, dbText);

        BackupOptions options;
        options.appVersion = "2.2.1-test";
        options.createdAt = "2026-10-26T10:00:00Z";

        const pb::BackupResult result =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath), options);
        CHECK_TRUE(result.ok);
        CHECK_TRUE(CodeIs(result.code, pb::backup_codes::kOk));
        CHECK_TRUE(result.error.empty());
        CHECK_TRUE(fs::exists(zipPath));
        CHECK_TRUE(FileSizeIs(zipPath, result.archiveBytes));
        CHECK_TRUE(result.archiveBytes > 0);

        // 清单字段
        CHECK_EQ(result.manifest.format, std::string("particlebook-backup"));
        CHECK_EQ(result.manifest.formatVersion, pb::kBackupFormatVersion);
        CHECK_EQ(result.manifest.appVersion, std::string("2.2.1-test"));
        CHECK_EQ(result.manifest.schemaVersion, 2);
        CHECK_EQ(result.manifest.createdAt, std::string("2026-10-26T10:00:00Z"));
        CHECK_EQ(result.manifest.bookCount, 2);
        CHECK_EQ(result.manifest.dbBytes, static_cast<std::uint64_t>(dbText.size()));
        CHECK_EQ(result.manifest.sha256, BackupService::Sha256Hex(dbText));
        CHECK_EQ(result.manifest.sha256.size(), static_cast<std::size_t>(64));

        // 真的是 zip（本地文件头 "PK\x03\x04"），且源库没被改动
        const std::string archive = ReadText(zipPath);
        CHECK_TRUE(archive.size() > 4);
        CHECK_EQ(archive.compare(0, 4, "PK\x03\x04"), 0);
        CHECK_EQ(ReadText(dbPath), dbText);
        CHECK_EQ(CountFilesContaining(dir, ".pb-tmp-"), 0);

        // InspectBackup 与创建时返回的清单一致（只读清单，不读 payload）
        BackupManifest inspected;
        std::string inspectCode;
        CHECK_TRUE(BackupService::InspectBackup(U8(zipPath), inspected, &inspectCode));
        CHECK_TRUE(inspectCode.empty());
        CHECK_EQ(inspected.appVersion, result.manifest.appVersion);
        CHECK_EQ(inspected.schemaVersion, result.manifest.schemaVersion);
        CHECK_EQ(inspected.createdAt, result.manifest.createdAt);
        CHECK_EQ(inspected.bookCount, result.manifest.bookCount);
        CHECK_EQ(inspected.sha256, result.manifest.sha256);
        CHECK_EQ(inspected.dbBytes, result.manifest.dbBytes);

        // 清空目录后包还在（zip 不依赖临时文件）
        fs::remove_all(dir);
    }

    // ── 3. 恢复成功：内容逐字节一致 + 恢复前自动备份是「旧内容」───────
    {
        const fs::path dir = MakeScratch("restore");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "backup.zip";
        const std::string backupText = MakeDatabase(2, 2);
        const std::string liveText = MakeDatabase(2, 3);  // 恢复前的当前库（更新）

        WriteText(dbPath, backupText);
        BackupOptions options;
        options.appVersion = "2.2.1-test";
        options.createdAt = "2026-10-26T10:00:00Z";
        const pb::BackupResult created =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath), options);
        CHECK_TRUE(created.ok);

        // 之后用户又导入了一本书（当前库 3 本）
        WriteText(dbPath, liveText);

        const pb::RestoreResult restored =
            BackupService::RestoreBackup(U8(zipPath), U8(dbPath));
        CHECK_TRUE(restored.ok);
        CHECK_TRUE(CodeIs(restored.code, pb::backup_codes::kOk));
        CHECK_TRUE(restored.error.empty());
        CHECK_EQ(restored.restoredPath, U8(dbPath));
        CHECK_EQ(restored.restoredBytes, static_cast<std::uint64_t>(backupText.size()));
        CHECK_EQ(restored.bookCount, 2);
        CHECK_EQ(restored.manifest.sha256, created.manifest.sha256);

        // 数据一致（表里字段没被重排 / 重新序列化）
        CHECK_EQ(ReadText(dbPath), backupText);

        // 恢复前自动备份：存在、内容是恢复前的 3 本库
        CHECK_TRUE(!restored.preRestoreBackupPath.empty());
        const fs::path preRestore = fs::path(restored.preRestoreBackupPath);
        CHECK_TRUE(fs::exists(preRestore));
        CHECK_EQ(ReadText(preRestore), liveText);
        CHECK_EQ(preRestore.parent_path(), dir);
        CHECK_TRUE(preRestore.filename().u8string().find(".pre-restore-") != std::string::npos);
        CHECK_EQ(CountFilesWithPrefix(dir, "reader.pre-restore-"), 1);
        CHECK_EQ(CountFilesContaining(dir, ".pb-tmp-"), 0);
    }

    // ── 4. 目标不存在：不产生 pre-restore 备份，直接落盘 ───────────────
    {
        const fs::path dir = MakeScratch("restore-fresh");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "backup.zip";
        const std::string backupText = MakeDatabase(2, 1);
        WriteText(dbPath, backupText);

        BackupOptions options;
        options.createdAt = "2026-10-26T10:00:00Z";
        CHECK_TRUE(BackupService::CreateBackup(U8(dbPath), U8(zipPath), options).ok);

        std::error_code ec;
        fs::remove(dbPath, ec);
        CHECK_TRUE(!fs::exists(dbPath));

        const pb::RestoreResult restored =
            BackupService::RestoreBackup(U8(zipPath), U8(dbPath));
        CHECK_TRUE(restored.ok);
        CHECK_TRUE(restored.preRestoreBackupPath.empty());
        CHECK_EQ(ReadText(dbPath), backupText);
        CHECK_EQ(CountFilesWithPrefix(dir, "reader.pre-restore-"), 0);
    }

    // ── 5. 关闭 pre-restore：不产生旧库副本 ────────────────────────────
    {
        const fs::path dir = MakeScratch("restore-nobackup");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "backup.zip";
        const std::string backupText = MakeDatabase(2, 2);
        WriteText(dbPath, backupText);
        BackupOptions options;
        options.createdAt = "2026-10-26T10:00:00Z";
        CHECK_TRUE(BackupService::CreateBackup(U8(dbPath), U8(zipPath), options).ok);

        WriteText(dbPath, MakeDatabase(2, 5));
        RestoreOptions restoreOptions;
        restoreOptions.makePreRestoreBackup = false;
        const pb::RestoreResult restored =
            BackupService::RestoreBackup(U8(zipPath), U8(dbPath), restoreOptions);
        CHECK_TRUE(restored.ok);
        CHECK_TRUE(restored.preRestoreBackupPath.empty());
        CHECK_EQ(ReadText(dbPath), backupText);
        CHECK_EQ(CountFilesWithPrefix(dir, "reader.pre-restore-"), 0);
    }

    // ── 6. store（compress=false）的包也能恢复 ─────────────────────────
    {
        const fs::path dir = MakeScratch("store");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "backup.zip";
        const std::string dbText = MakeDatabase(2, 1);
        WriteText(dbPath, dbText);

        BackupOptions options;
        options.compress = false;
        options.createdAt = "2026-10-26T10:00:00Z";
        const pb::BackupResult created =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath), options);
        CHECK_TRUE(created.ok);

        WriteText(dbPath, MakeDatabase(2, 9));
        CHECK_TRUE(BackupService::RestoreBackup(U8(zipPath), U8(dbPath)).ok);
        CHECK_EQ(ReadText(dbPath), dbText);
    }

    // ── 7. 损坏 zip（截断）被拒绝，目标库一个字节都不动 ────────────────
    {
        const fs::path dir = MakeScratch("corrupt-truncated");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "backup.zip";
        const std::string backupText = MakeDatabase(2, 2);
        WriteText(dbPath, backupText);
        CHECK_TRUE(BackupService::CreateBackup(U8(dbPath), U8(zipPath)).ok);

        std::string archive = ReadText(zipPath);
        CHECK_TRUE(archive.size() > 40);
        archive.resize(archive.size() - 8);  // 砍掉 EOCD 尾部
        WriteText(zipPath, archive);

        WriteText(dbPath, MakeDatabase(2, 7));
        const std::string liveText = ReadText(dbPath);
        const pb::RestoreResult restored =
            BackupService::RestoreBackup(U8(zipPath), U8(dbPath));
        CHECK_TRUE(!restored.ok);
        CHECK_TRUE(CodeIs(restored.code, pb::backup_codes::kManifestMissing));
        CHECK_EQ(ReadText(dbPath), liveText);
        CHECK_EQ(CountFilesWithPrefix(dir, "reader.pre-restore-"), 0);

        BackupManifest manifest;
        std::string code;
        CHECK_TRUE(!BackupService::InspectBackup(U8(zipPath), manifest, &code));
        CHECK_TRUE(CodeIs(code, pb::backup_codes::kManifestMissing));
    }

    // ── 8. 是 zip 但没有 manifest → 不是本应用的备份 ─────────────────
    {
        const fs::path dir = MakeScratch("empty-zip");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "empty.zip";
        WriteText(dbPath, MakeDatabase(2, 1));
        WriteText(zipPath, EmptyZip());

        BackupManifest manifest;
        std::string code;
        CHECK_TRUE(!BackupService::InspectBackup(U8(zipPath), manifest, &code));
        CHECK_TRUE(CodeIs(code, pb::backup_codes::kManifestMissing));

        const std::string liveText = ReadText(dbPath);
        const pb::RestoreResult restored =
            BackupService::RestoreBackup(U8(zipPath), U8(dbPath));
        CHECK_TRUE(!restored.ok);
        CHECK_TRUE(CodeIs(restored.code, pb::backup_codes::kManifestMissing));
        CHECK_EQ(ReadText(dbPath), liveText);
    }

    // ── 9. manifest 的 sha256 被篡改 → checksum_mismatch，目标不动 ─────
    {
        const fs::path dir = MakeScratch("tampered");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "backup.zip";
        const std::string backupText = MakeDatabase(2, 2);
        WriteText(dbPath, backupText);
        BackupOptions options;
        options.createdAt = "2026-10-26T10:00:00Z";
        CHECK_TRUE(BackupService::CreateBackup(U8(dbPath), U8(zipPath), options).ok);

        BackupManifest inspected;
        CHECK_TRUE(BackupService::InspectBackup(U8(zipPath), inspected));
        CHECK_EQ(inspected.sha256.size(), static_cast<std::size_t>(64));

        // manifest.json 是 store 的，sha 在包里是明文；改一个 hex 位即可让
        // 清单与 payload 不一致（zip 结构 / 偏移 / 长度全部不变）。
        std::string archive = ReadText(zipPath);
        const std::size_t at = archive.find(inspected.sha256);
        CHECK_TRUE(at != std::string::npos);
        archive[at] = (archive[at] == 'f') ? '0' : 'f';
        WriteText(zipPath, archive);

        WriteText(dbPath, MakeDatabase(2, 6));
        const std::string liveText = ReadText(dbPath);
        const pb::RestoreResult restored =
            BackupService::RestoreBackup(U8(zipPath), U8(dbPath));
        CHECK_TRUE(!restored.ok);
        CHECK_TRUE(CodeIs(restored.code, pb::backup_codes::kChecksumMismatch));
        CHECK_EQ(ReadText(dbPath), liveText);
        CHECK_EQ(CountFilesWithPrefix(dir, "reader.pre-restore-"), 0);
    }

    // ── 10. schema 比本应用新 → schema_too_new（降级保护），目标不动 ───
    {
        const fs::path dir = MakeScratch("schema-newer");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "backup.zip";
        const std::string futureText = MakeDatabase(99, 2);
        WriteText(dbPath, futureText);

        BackupOptions options;
        options.createdAt = "2026-10-26T10:00:00Z";
        const pb::BackupResult created =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath), options);
        CHECK_TRUE(created.ok);
        CHECK_EQ(created.manifest.schemaVersion, 99);  // 从 reader.json 自动推断

        // 当前库换回本版本（2），再尝试恢复 99 的备份
        const std::string liveText = MakeDatabase(2, 1);
        WriteText(dbPath, liveText);
        const pb::RestoreResult restored =
            BackupService::RestoreBackup(U8(zipPath), U8(dbPath));  // 默认 = kCurrentSchemaVersion
        CHECK_TRUE(!restored.ok);
        CHECK_TRUE(CodeIs(restored.code, pb::backup_codes::kSchemaTooNew));
        CHECK_TRUE(restored.error.find("99") != std::string::npos);
        CHECK_EQ(ReadText(dbPath), liveText);
        CHECK_EQ(CountFilesWithPrefix(dir, "reader.pre-restore-"), 0);

        // 显式放宽 schema 版本就能恢复（将来做「迁移后导入」时用得到）
        RestoreOptions relaxed;
        relaxed.currentSchemaVersion = 99;
        const pb::RestoreResult forced =
            BackupService::RestoreBackup(U8(zipPath), U8(dbPath), relaxed);
        CHECK_TRUE(forced.ok);
        CHECK_EQ(ReadText(dbPath), futureText);
    }

    // ── 11. 入口参数 / 坏数据库：拒绝且不产出 zip ──────────────────────
    {
        const fs::path dir = MakeScratch("inputs");
        const fs::path dbPath = dir / "reader.json";
        const fs::path zipPath = dir / "backup.zip";

        // 不存在的库
        pb::BackupResult missing =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath));
        CHECK_TRUE(!missing.ok);
        CHECK_TRUE(CodeIs(missing.code, pb::backup_codes::kDbMissing));
        CHECK_TRUE(!fs::exists(zipPath));

        // 空文件
        WriteText(dbPath, std::string());
        pb::BackupResult empty =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath));
        CHECK_TRUE(!empty.ok);
        CHECK_TRUE(CodeIs(empty.code, pb::backup_codes::kDbEmpty));
        CHECK_TRUE(!fs::exists(zipPath));

        // 不是 JSON 对象
        WriteText(dbPath, "{ this is not json ");
        pb::BackupResult invalid =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath));
        CHECK_TRUE(!invalid.ok);
        CHECK_TRUE(CodeIs(invalid.code, pb::backup_codes::kInvalidDb));
        CHECK_TRUE(!fs::exists(zipPath));

        // 空路径
        pb::BackupResult noPath =
            BackupService::CreateBackup(std::string(), U8(zipPath));
        CHECK_TRUE(!noPath.ok);
        CHECK_TRUE(CodeIs(noPath.code, pb::backup_codes::kInvalidOptions));

        // createdAt 格式非法
        WriteText(dbPath, MakeDatabase(2, 1));
        BackupOptions badTime;
        badTime.createdAt = "not-a-timestamp";
        pb::BackupResult badCreatedAt =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath), badTime);
        CHECK_TRUE(!badCreatedAt.ok);
        CHECK_TRUE(CodeIs(badCreatedAt.code, pb::backup_codes::kInvalidOptions));

        // 恢复：备份文件不存在
        const pb::RestoreResult noBackup =
            BackupService::RestoreBackup(U8(dir / "nope.zip"), U8(dbPath));
        CHECK_TRUE(!noBackup.ok);
        CHECK_TRUE(CodeIs(noBackup.code, pb::backup_codes::kBackupMissing));

        CHECK_EQ(CountFilesContaining(dir, ".pb-tmp-"), 0);
    }

    // ── 12. 输出目录不存在 → io_error（不崩、不产生半截文件）───────────
    {
        const fs::path dir = MakeScratch("no-outdir");
        const fs::path dbPath = dir / "reader.json";
        WriteText(dbPath, MakeDatabase(2, 1));
        const fs::path zipPath = dir / "missing-subdir" / "backup.zip";
        const pb::BackupResult result =
            BackupService::CreateBackup(U8(dbPath), U8(zipPath));
        CHECK_TRUE(!result.ok);
        CHECK_TRUE(CodeIs(result.code, pb::backup_codes::kIoError));
        CHECK_TRUE(!fs::exists(zipPath));
    }

    // ── 13. ListBackups：只列 *.zip，按时间倒序 ────────────────────────
    {
        const fs::path dir = MakeScratch("list");
        const fs::path dbPath = dir / "reader.json";
        WriteText(dbPath, MakeDatabase(2, 1));

        BackupOptions older;
        older.createdAt = "2026-10-26T10:00:00Z";
        BackupOptions newer;
        newer.createdAt = "2026-10-26T11:00:00Z";
        const fs::path olderZip = dir / BackupService::DefaultBackupFileName(older.createdAt);
        const fs::path newerZip = dir / BackupService::DefaultBackupFileName(newer.createdAt);
        CHECK_TRUE(BackupService::CreateBackup(U8(dbPath), U8(olderZip), older).ok);
        CHECK_TRUE(BackupService::CreateBackup(U8(dbPath), U8(newerZip), newer).ok);
        WriteText(dir / "notes.txt", "not a backup");

        const std::vector<pb::BackupFileInfo> backups =
            BackupService::ListBackups(U8(dir));
        CHECK_EQ(backups.size(), static_cast<std::size_t>(2));
        if (backups.size() == 2) {
            CHECK_EQ(backups[0].fileName, newerZip.filename().u8string());
            CHECK_EQ(backups[1].fileName, olderZip.filename().u8string());
            CHECK_TRUE(backups[0].bytes > 0);
            CHECK_TRUE(backups[0].modifiedEpoch > 0);
            CHECK_EQ(backups[0].path, U8(newerZip));
        }
        CHECK_TRUE(BackupService::ListBackups(U8(dir / "nope")).empty());
    }

    // ── 14. 文件名 / 时间工具 ─────────────────────────────────────────
    {
        CHECK_EQ(BackupService::DefaultBackupFileName("2026-10-26T10:00:00Z"),
                 std::string("particlebook-backup-20261026-100000.zip"));
        const std::string pre = BackupService::PreRestoreNameFor(
            "C:\\Users\\x\\AppData\\Roaming\\ParticleBook\\reader.json",
            "2026-10-26T10:00:00Z");
        CHECK_EQ(pre, std::string("C:\\Users\\x\\AppData\\Roaming\\ParticleBook\\"
                                  "reader.pre-restore-20261026-100000.json"));

        const std::string now = BackupService::NowUtcIso8601();
        CHECK_EQ(now.size(), static_cast<std::size_t>(20));
        if (now.size() == 20) {
            CHECK_EQ(now[4], '-');
            CHECK_EQ(now[7], '-');
            CHECK_EQ(now[10], 'T');
            CHECK_EQ(now[19], 'Z');
        }
        CHECK_TRUE(BackupService::DefaultBackupFileName().find(
                       "particlebook-backup-") == 0);
    }

    std::error_code ec;
    fs::remove_all(fs::temp_directory_path() / "pb_backup_test", ec);
    return pb_t::Summary("backup_service_test");
}
