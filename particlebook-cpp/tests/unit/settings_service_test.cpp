// ── PB-023: SettingsService 单元测试 ───────────────────────────────
// 不依赖 Win32 / 数据库 / 渲染层：直接链接 SettingsService.cpp。
//
// 覆盖（对应 docs/BACKLOG.md PB-023 验收 1/2/3）：
//   * schema：默认值与渲染层一致、全局/书级归属、schema 自描述
//   * Normalize：空/部分输入、类型错误、范围夹紧、枚举回退、legacy 迁移、
//     版本号（旧数据升级 / 新版本保留）
//   * 未知键三策略（Preserve / Drop / Reject）
//   * 书级：只保留排版键、不填默认值；ResolveEffective 的覆盖顺序
//   * Validate 严格体检 / IsValid；MergeDefaults
//   * Serialize/Deserialize：round-trip、文本稳定、坏 JSON、非对象 JSON
//   * 密钥边界：疑似密钥键被丢弃、嵌套脱敏、序列化不含明文 Key、
//     baseUrl 内置凭证告警（settings 只存非敏感项，Key 归 SecretStore）
//   * schema 自身不含疑似密钥的键

#include "platform/SettingsService.h"

#include "utils/test_assert.h"

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

using json = nlohmann::json;
using pb::SettingsIssue;
using pb::SettingsOptions;
using pb::SettingsService;
using pb::SettingsSeverity;
using pb::UnknownKeyPolicy;

namespace {

bool HasIssue(const std::vector<SettingsIssue>& issues, const std::string& code)
{
    for (const SettingsIssue& issue : issues) {
        if (issue.code == code) return true;
    }
    return false;
}

bool HasIssue(const std::vector<SettingsIssue>& issues, const std::string& code,
              const std::string& key)
{
    for (const SettingsIssue& issue : issues) {
        if (issue.code == code && issue.key == key) return true;
    }
    return false;
}

bool Contains(const std::vector<std::string>& values, const std::string& wanted)
{
    for (const std::string& value : values) {
        if (value == wanted) return true;
    }
    return false;
}

int CountWithSeverity(const std::vector<SettingsIssue>& issues, SettingsSeverity severity)
{
    int count = 0;
    for (const SettingsIssue& issue : issues) {
        if (issue.severity == severity) ++count;
    }
    return count;
}

std::string Lower(std::string text)
{
    for (char& c : text) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return text;
}

// json 的 CHECK_EQ 没有 Show 重载，打印 dump 便于定位。
void CheckJsonEq(const char* what, const json& left, const json& right)
{
    if (left == right) return;
    ++pb_t::g_failures;
    std::printf("FAIL  %s\n", what);
    std::printf("        left : %s\n", left.dump().c_str());
    std::printf("        right: %s\n", right.dump().c_str());
}

// ── schema / 默认值 ───────────────────────────────────────────────

void TestSchemaAndDefaults()
{
    const std::vector<pb::SettingSpec>& schema = SettingsService::Schema();
    CHECK_TRUE(schema.size() >= 30);

    const pb::SettingSpec* fontSize = SettingsService::Find("fontSize");
    CHECK_TRUE(fontSize != nullptr);
    CHECK_TRUE(fontSize->type == pb::SettingSpec::Type::Int);
    CHECK_TRUE(fontSize->bookScoped);

    const pb::SettingSpec* theme = SettingsService::Find("theme");
    CHECK_TRUE(theme != nullptr);
    CHECK_TRUE(!theme->bookScoped);

    const pb::SettingSpec* baseUrl = SettingsService::Find("translation.baseUrl");
    CHECK_TRUE(baseUrl != nullptr);
    CHECK_TRUE(baseUrl->isUrl);

    CHECK_TRUE(SettingsService::Find("schemaVersion") == nullptr);
    CHECK_TRUE(SettingsService::Find("nope.nothing") == nullptr);
    CHECK_TRUE(SettingsService::IsBookKey("fontFamily"));
    CHECK_TRUE(!SettingsService::IsBookKey("theme"));
    CHECK_TRUE(!SettingsService::IsBookKey("tts.voice"));

    const std::vector<std::string> bookKeys = SettingsService::BookKeys();
    CHECK_EQ(static_cast<int>(bookKeys.size()), 5);
    CHECK_TRUE(Contains(bookKeys, "fontSize"));
    CHECK_TRUE(Contains(bookKeys, "fontFamily"));
    CHECK_TRUE(Contains(bookKeys, "lineHeight"));
    CHECK_TRUE(Contains(bookKeys, "margin"));
    CHECK_TRUE(Contains(bookKeys, "textAlign"));

    // schema 自描述（将来渲染层据此生成表单）
    const json schemaJson = SettingsService::SchemaJson();
    CHECK_TRUE(schemaJson.is_array());
    CHECK_EQ(static_cast<int>(schemaJson.size()), static_cast<int>(schema.size()));
    bool foundFontSize = false;
    for (const json& item : schemaJson) {
        if (item.at("key").get<std::string>() == "fontSize") {
            foundFontSize = true;
            CHECK_TRUE(item.at("bookScoped").get<bool>());
            CHECK_EQ(item.at("type").get<std::string>(), std::string("int"));
            CHECK_EQ(item.at("default").get<int>(), 18);
        }
    }
    CHECK_TRUE(foundFontSize);

    // schema 自己不能包含疑似密钥的键（否则 Normalize 会把自己声明的键拒掉）
    for (const pb::SettingSpec& spec : schema) {
        CHECK_TRUE(!SettingsService::IsSecretLikeKey(spec.key));
    }

    const json defaults = SettingsService::Defaults();
    CHECK_EQ(defaults.at("schemaVersion").get<int>(), SettingsService::kSchemaVersion);
    CHECK_EQ(defaults.at("theme").get<std::string>(), std::string("dark"));
    CHECK_EQ(defaults.at("accentColor").get<std::string>(), std::string("blue"));
    CHECK_EQ(defaults.at("fontSize").get<int>(), 18);
    CHECK_EQ(defaults.at("fontFamily").get<std::string>(),
             std::string("Georgia, Noto Serif SC, serif"));
    CHECK_TRUE(std::fabs(defaults.at("lineHeight").get<double>() - 1.8) < 1e-9);
    CHECK_EQ(defaults.at("margin").get<int>(), 40);
    CHECK_EQ(defaults.at("textAlign").get<std::string>(), std::string("justify"));
    CHECK_TRUE(defaults.at("autoSaveProgress").get<bool>());
    CHECK_TRUE(defaults.at("showReadingTime").get<bool>());
    CHECK_EQ(defaults.at("defaultViewMode").get<std::string>(), std::string("grid"));
    CHECK_EQ(defaults.at("defaultSortBy").get<std::string>(), std::string("last_opened"));
    CHECK_EQ(defaults.at("language").get<std::string>(), std::string("zh"));
    CHECK_TRUE(!defaults.at("tts.enabled").get<bool>());
    CHECK_TRUE(!defaults.at("translation.enabled").get<bool>());
    CHECK_EQ(defaults.at("backup.path").get<std::string>(), std::string(""));
    CHECK_EQ(static_cast<int>(SettingsService::BookDefaults().size()), 5);
    CHECK_TRUE(!SettingsService::BookDefaults().contains("schemaVersion"));
}

// ── Normalize：默认值 / 类型 / 范围 / 枚举 / 迁移 / 版本 ──────────

void TestNormalizeBasics()
{
    const pb::SettingsResult empty = SettingsService::Normalize(json::object());
    CHECK_TRUE(!empty.HasErrors());
    CHECK_EQ(static_cast<int>(empty.issues.size()), 0);
    CheckJsonEq("empty normalize == defaults", empty.values, SettingsService::Defaults());

    const pb::SettingsResult nullValue = SettingsService::Normalize(json());
    CHECK_TRUE(!nullValue.HasErrors());
    CheckJsonEq("null normalize == defaults", nullValue.values, SettingsService::Defaults());

    const pb::SettingsResult partial = SettingsService::Normalize(json{{"theme", "light"}});
    CHECK_TRUE(!partial.HasErrors());
    CHECK_EQ(partial.values.at("theme").get<std::string>(), std::string("light"));
    CHECK_EQ(partial.values.at("fontSize").get<int>(), 18);
    CHECK_EQ(static_cast<int>(partial.values.size()),
             static_cast<int>(SettingsService::Schema().size()) + 1);

    // 类型错误 -> Error + 默认值
    const pb::SettingsResult wrongTypes =
        SettingsService::Normalize(json{{"fontSize", "big"},
                                        {"autoSaveProgress", "yes"},
                                        {"theme", 42},
                                        {"lineHeight", true}});
    CHECK_TRUE(wrongTypes.HasErrors());
    CHECK_TRUE(HasIssue(wrongTypes.issues, "type", "fontSize"));
    CHECK_TRUE(HasIssue(wrongTypes.issues, "type", "autoSaveProgress"));
    CHECK_TRUE(HasIssue(wrongTypes.issues, "type", "theme"));
    CHECK_TRUE(HasIssue(wrongTypes.issues, "type", "lineHeight"));
    CHECK_EQ(wrongTypes.values.at("fontSize").get<int>(), 18);
    CHECK_TRUE(wrongTypes.values.at("autoSaveProgress").get<bool>());
    CHECK_EQ(wrongTypes.values.at("theme").get<std::string>(), std::string("dark"));
    CHECK_TRUE(std::fabs(wrongTypes.values.at("lineHeight").get<double>() - 1.8) < 1e-9);

    // 范围 -> Warning + 夹紧（不丢用户意图）
    const pb::SettingsResult range =
        SettingsService::Normalize(json{{"fontSize", 999},
                                        {"margin", -5},
                                        {"lineHeight", 9.9},
                                        {"tts.rate", 99}});
    CHECK_TRUE(!range.HasErrors());
    CHECK_TRUE(HasIssue(range.issues, "range", "fontSize"));
    CHECK_TRUE(HasIssue(range.issues, "range", "margin"));
    CHECK_TRUE(HasIssue(range.issues, "range", "lineHeight"));
    CHECK_TRUE(HasIssue(range.issues, "range", "tts.rate"));
    CHECK_EQ(range.values.at("fontSize").get<int>(), 32);
    CHECK_EQ(range.values.at("margin").get<int>(), 0);
    CHECK_TRUE(std::fabs(range.values.at("lineHeight").get<double>() - 3.0) < 1e-9);
    CHECK_EQ(range.values.at("tts.rate").get<int>(), 10);

    // 枚举非法值 -> Warning + 默认值（前向兼容：新版本枚举值在这里回退）
    const pb::SettingsResult enumFallback =
        SettingsService::Normalize(json{{"theme", "pink"}, {"textAlign", "center"}});
    CHECK_TRUE(!enumFallback.HasErrors());
    CHECK_TRUE(HasIssue(enumFallback.issues, "enum", "theme"));
    CHECK_TRUE(HasIssue(enumFallback.issues, "enum", "textAlign"));
    CHECK_EQ(enumFallback.values.at("theme").get<std::string>(), std::string("dark"));
    CHECK_EQ(enumFallback.values.at("textAlign").get<std::string>(), std::string("justify"));

    // 长度上限 -> Error + 默认值
    const std::string huge(201, 'x');
    const pb::SettingsResult tooLong =
        SettingsService::Normalize(json{{"fontFamily", huge}});
    CHECK_TRUE(tooLong.HasErrors());
    CHECK_TRUE(HasIssue(tooLong.issues, "length", "fontFamily"));
    CHECK_EQ(tooLong.values.at("fontFamily").get<std::string>(),
             std::string("Georgia, Noto Serif SC, serif"));
}

void TestLegacyMigrationAndVersion()
{
    // 老数据 / 渲染层字符串化："18" / "false" 迁移，给 Warning 不是 Error
    const pb::SettingsResult migrated =
        SettingsService::Normalize(json{{"fontSize", "20"},
                                        {"lineHeight", "1.5"},
                                        {"autoSaveProgress", "false"},
                                        {"tts.volume", "80"}});
    CHECK_TRUE(!migrated.HasErrors());
    CHECK_TRUE(HasIssue(migrated.issues, "migrated", "fontSize"));
    CHECK_EQ(migrated.values.at("fontSize").get<int>(), 20);
    CHECK_TRUE(std::fabs(migrated.values.at("lineHeight").get<double>() - 1.5) < 1e-9);
    CHECK_TRUE(!migrated.values.at("autoSaveProgress").get<bool>());
    CHECK_EQ(migrated.values.at("tts.volume").get<int>(), 80);

    // 关掉迁移则按类型错误处理
    SettingsOptions strict;
    strict.migrateLegacyTypes = false;
    const pb::SettingsResult strictResult =
        SettingsService::Normalize(json{{"fontSize", "20"}}, strict);
    CHECK_TRUE(strictResult.HasErrors());
    CHECK_TRUE(HasIssue(strictResult.issues, "type", "fontSize"));
    CHECK_EQ(strictResult.values.at("fontSize").get<int>(), 18);

    // 旧数据没有版本号 -> 升级到当前版本，不是错误
    const pb::SettingsResult legacy = SettingsService::Normalize(json{{"theme", "sepia"}});
    CHECK_TRUE(!legacy.HasErrors());
    CHECK_EQ(legacy.values.at("schemaVersion").get<int>(), SettingsService::kSchemaVersion);

    // 更新版本写的数据：保留版本号 + 未知键，只给 Warning，不降级破坏
    const pb::SettingsResult newer =
        SettingsService::Normalize(json{{"schemaVersion", 2}, {"future.thing", 7}});
    CHECK_TRUE(!newer.HasErrors());
    CHECK_TRUE(HasIssue(newer.issues, "version", "schemaVersion"));
    CHECK_EQ(newer.values.at("schemaVersion").get<int>(), 2);
    CHECK_TRUE(newer.values.contains("future.thing"));

    // 非法版本号 -> Error + 回到当前版本
    for (const json& bad : {json{{"schemaVersion", "x"}}, json{{"schemaVersion", 0}},
                            json{{"schemaVersion", -3}}}) {
        const pb::SettingsResult result = SettingsService::Normalize(bad);
        CHECK_TRUE(result.HasErrors());
        CHECK_TRUE(HasIssue(result.issues, "version", "schemaVersion"));
        CHECK_EQ(result.values.at("schemaVersion").get<int>(), SettingsService::kSchemaVersion);
    }

    // 版本号字符串也算 legacy 迁移
    const pb::SettingsResult stringVersion =
        SettingsService::Normalize(json{{"schemaVersion", "1"}});
    CHECK_TRUE(!stringVersion.HasErrors());
    CHECK_EQ(stringVersion.values.at("schemaVersion").get<int>(), 1);
}

void TestUnknownKeyPolicies()
{
    const json raw = json{{"theme", "light"},
                          {"zlibDownloadPath", "D:/Downloads"},
                          {"future.thing", json{{"a", 1}}}};

    // Preserve（默认）：未知键原样保留，前向兼容 + handler 私有键不丢
    const pb::SettingsResult preserve = SettingsService::Normalize(raw);
    CHECK_TRUE(!preserve.HasErrors());
    CHECK_EQ(preserve.values.at("zlibDownloadPath").get<std::string>(),
             std::string("D:/Downloads"));
    CHECK_EQ(preserve.values.at("future.thing").at("a").get<int>(), 1);
    CHECK_TRUE(HasIssue(preserve.issues, "unknown", "zlibDownloadPath"));
    CHECK_TRUE(HasIssue(preserve.issues, "unknown", "future.thing"));

    // Drop：丢弃 + Warning
    SettingsOptions drop;
    drop.unknownKeys = UnknownKeyPolicy::Drop;
    const pb::SettingsResult dropped = SettingsService::Normalize(raw, drop);
    CHECK_TRUE(!dropped.HasErrors());
    CHECK_TRUE(!dropped.values.contains("zlibDownloadPath"));
    CHECK_TRUE(!dropped.values.contains("future.thing"));
    CHECK_TRUE(HasIssue(dropped.issues, "unknown", "zlibDownloadPath"));

    // Reject：丢弃 + Error
    SettingsOptions reject;
    reject.unknownKeys = UnknownKeyPolicy::Reject;
    const pb::SettingsResult rejected = SettingsService::Normalize(raw, reject);
    CHECK_TRUE(rejected.HasErrors());
    CHECK_TRUE(!rejected.values.contains("zlibDownloadPath"));
    CHECK_TRUE(HasIssue(rejected.issues, "unknown", "zlibDownloadPath"));
}

// ── 书级覆盖 ─────────────────────────────────────────────────────

void TestBookSettings()
{
    const json globalRaw = json{{"theme", "sepia"},
                                {"fontSize", 15},
                                {"margin", 50},
                                {"junkGlobal", true}};
    const json bookRaw = json{{"fontSize", 20},
                              {"theme", "light"},
                              {"autoSaveProgress", false},
                              {"junkBook", 1}};

    const pb::SettingsResult book = SettingsService::NormalizeBook(bookRaw);
    CHECK_TRUE(!book.HasErrors());
    CHECK_EQ(book.values.at("fontSize").get<int>(), 20);
    // 不填默认值：没写过的书级键不能出现，否则会顶掉全局值
    CHECK_TRUE(!book.values.contains("margin"));
    CHECK_TRUE(!book.values.contains("lineHeight"));
    // 历史遗留的全局键（旧版误写进书级行）被忽略
    CHECK_TRUE(!book.values.contains("theme"));
    CHECK_TRUE(!book.values.contains("autoSaveProgress"));
    CHECK_TRUE(HasIssue(book.issues, "scope", "theme"));
    CHECK_TRUE(HasIssue(book.issues, "scope", "autoSaveProgress"));
    // 未知键默认保留（但 ResolveEffective 不会采用）
    CHECK_TRUE(book.values.contains("junkBook"));
    // schemaVersion 是全局元数据，书级行里出现也不生效
    const pb::SettingsResult bookWithVersion =
        SettingsService::NormalizeBook(json{{"fontSize", 16}, {"schemaVersion", 1}});
    CHECK_TRUE(!bookWithVersion.values.contains("schemaVersion"));
    CHECK_TRUE(HasIssue(bookWithVersion.issues, "version", "schemaVersion"));

    // 有效设置：全局规范化值 + 书级排版键覆盖
    const json effective = SettingsService::ResolveEffective(globalRaw, bookRaw);
    CHECK_EQ(effective.at("fontSize").get<int>(), 20);
    CHECK_EQ(effective.at("margin").get<int>(), 50);
    CHECK_EQ(effective.at("theme").get<std::string>(), std::string("sepia"));
    CHECK_EQ(effective.at("lineHeight").get<double>(), 1.8);
    CHECK_TRUE(effective.contains("junkGlobal"));   // 全局未知键仍在
    CHECK_TRUE(!effective.contains("junkBook"));    // 书级未知键不进有效设置

    // 没有书级行时与全局规范化结果一致
    CheckJsonEq("no book overrides",
                SettingsService::ResolveEffective(globalRaw, json::object()),
                SettingsService::Normalize(globalRaw).values);

    // 书级坏值：范围夹紧后覆盖；类型错误回退默认值也不应把全局顶掉？
    // 设计选择：NormalizeBook 对非法值仍产出合法值（夹紧 / 默认），书级行
    // 一旦写了这个键就代表用户在该书里改过它。
    const json clampBook = SettingsService::ResolveEffective(
        json{{"fontSize", 15}}, json{{"fontSize", 999}});
    CHECK_EQ(clampBook.at("fontSize").get<int>(), 32);
}

// ── Validate / MergeDefaults / 类型化读取 ────────────────────────

void TestValidateAndMerge()
{
    CHECK_EQ(static_cast<int>(SettingsService::Validate(SettingsService::Defaults()).size()), 0);
    CHECK_TRUE(SettingsService::IsValid(SettingsService::Defaults()));

    const json dirty = json{{"fontSize", "big"},
                            {"theme", "pink"},
                            {"tts.volume", 500},
                            {"unknownKey", 1},
                            {"translation.apiKey", "sk-live-abc"}};
    const std::vector<SettingsIssue> issues = SettingsService::Validate(dirty);
    CHECK_TRUE(HasIssue(issues, "type", "fontSize"));
    CHECK_TRUE(HasIssue(issues, "enum", "theme"));
    CHECK_TRUE(HasIssue(issues, "range", "tts.volume"));
    CHECK_TRUE(HasIssue(issues, "unknown", "unknownKey"));
    CHECK_TRUE(HasIssue(issues, "secret", "translation.apiKey"));
    CHECK_TRUE(!SettingsService::IsValid(dirty));
    // 4 个 Error（type / enum / range / secret）+ 1 个 unknown Warning
    CHECK_EQ(CountWithSeverity(issues, SettingsSeverity::Error), 4);
    CHECK_EQ(CountWithSeverity(issues, SettingsSeverity::Warning), 1);

    // Normalize 之后只剩「未知键保留」Warning，可以安全使用
    const pb::SettingsResult repaired = SettingsService::Normalize(dirty);
    CHECK_TRUE(SettingsService::IsValid(repaired.values));
    CHECK_TRUE(!repaired.values.contains("translation.apiKey"));

    // 根不是对象
    CHECK_TRUE(!SettingsService::Validate(json::array()).empty());
    CHECK_TRUE(HasIssue(SettingsService::Validate(json::array()), "root"));

    // 书级体检：全局键 = Warning（忽略即可），非法书级值 = Error
    const std::vector<SettingsIssue> bookIssues = SettingsService::ValidateBook(
        json{{"fontSize", 999}, {"theme", "light"}, {"autoSaveProgress", false}});
    CHECK_TRUE(HasIssue(bookIssues, "range", "fontSize"));
    CHECK_TRUE(HasIssue(bookIssues, "scope", "theme"));
    CHECK_TRUE(HasIssue(bookIssues, "scope", "autoSaveProgress"));

    // MergeDefaults：只补默认值，不校验、不删未知键
    const json merged =
        SettingsService::MergeDefaults(json{{"theme", "light"}, {"fontSize", "oops"}});
    CHECK_EQ(merged.at("theme").get<std::string>(), std::string("light"));
    CHECK_EQ(merged.at("fontSize").get<std::string>(), std::string("oops"));
    CHECK_EQ(merged.at("margin").get<int>(), 40);
    CHECK_EQ(merged.at("schemaVersion").get<int>(), 1);
    CHECK_TRUE(!merged.contains("nope"));

    // 类型化读取
    const json settings = SettingsService::Normalize(json{{"fontSize", 22}}).values;
    CHECK_EQ(SettingsService::GetInt(settings, "fontSize"), 22LL);
    CHECK_EQ(SettingsService::GetInt(settings, "noSuchKey"), 0LL);
    CHECK_EQ(SettingsService::GetString(settings, "theme"), std::string());
    CHECK_EQ(SettingsService::GetString(settings, "fontFamily"),
             std::string("Georgia, Noto Serif SC, serif"));
    CHECK_TRUE(SettingsService::GetBool(settings, "autoSaveProgress"));
    CHECK_TRUE(std::fabs(SettingsService::GetDouble(settings, "lineHeight") - 1.8) < 1e-9);
    // 未规范化 / 类型错误的值回落到 schema 默认值
    const json badValues = json{{"fontSize", "huge"}, {"autoSaveProgress", "yes"}};
    CHECK_EQ(SettingsService::GetInt(badValues, "fontSize"), 18LL);
    CHECK_TRUE(SettingsService::GetBool(badValues, "autoSaveProgress"));
}

// ── 序列化 ───────────────────────────────────────────────────────

void TestSerializeRoundTrip()
{
    const json source = json{{"theme", "light"},
                             {"fontSize", 22},
                             {"lineHeight", 2.1},
                             {"fontFamily", "Inter, Noto Sans SC, sans-serif"},
                             {"translation.provider", "deepseek"},
                             {"translation.baseUrl", "https://api.deepseek.com/v1"},
                             {"translation.model", "deepseek-chat"},
                             {"zlibDownloadPath", "D:/Downloads"}};
    const pb::SettingsResult normalized = SettingsService::Normalize(source);
    CHECK_TRUE(!normalized.HasErrors());

    const std::string text = SettingsService::Serialize(source);
    CHECK_TRUE(text.find("\"schemaVersion\":1") != std::string::npos);
    CHECK_TRUE(text.find("D:/Downloads") != std::string::npos); // 未知键保留

    const pb::SettingsResult restored = SettingsService::Deserialize(text);
    CHECK_TRUE(!restored.HasErrors());
    CheckJsonEq("round-trip values", restored.values, normalized.values);
    CHECK_EQ(SettingsService::Serialize(restored.values), text); // 文本稳定

    // 坏 JSON -> 默认值 + parse Error
    const pb::SettingsResult bad = SettingsService::Deserialize("not json at all");
    CHECK_TRUE(bad.HasErrors());
    CHECK_TRUE(HasIssue(bad.issues, "parse"));
    CheckJsonEq("bad json -> defaults", bad.values, SettingsService::Defaults());

    // 合法 JSON 但不是对象
    const pb::SettingsResult array = SettingsService::Deserialize("[1,2,3]");
    CHECK_TRUE(array.HasErrors());
    CHECK_TRUE(HasIssue(array.issues, "root"));
    CheckJsonEq("array -> defaults", array.values, SettingsService::Defaults());

    // Serialize 不抛异常（任何输入都能产出字符串）
    CHECK_TRUE(!SettingsService::Serialize(json()).empty());
    CHECK_TRUE(!SettingsService::Serialize(json{{"fontSize", json::array()}}).empty());
}

// ── 密钥边界 ─────────────────────────────────────────────────────

void TestSecretBoundary()
{
    // 键名识别：真密钥键
    CHECK_TRUE(SettingsService::IsSecretLikeKey("translation.apiKey"));
    CHECK_TRUE(SettingsService::IsSecretLikeKey("API_KEY"));
    CHECK_TRUE(SettingsService::IsSecretLikeKey("openaiToken"));
    CHECK_TRUE(SettingsService::IsSecretLikeKey("provider.secret"));
    CHECK_TRUE(SettingsService::IsSecretLikeKey("auth"));
    CHECK_TRUE(SettingsService::IsSecretLikeKey("dbPassword"));
    CHECK_TRUE(SettingsService::IsSecretLikeKey("privateKey"));
    CHECK_TRUE(SettingsService::IsSecretLikeKey("key")); // 裸 key 视为密钥
    // 不误伤正常设置键（单独的 key 段不再一票命中，避免 shortcutKey/keyLength 误判）
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("shortcutKey"));
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("keyLength"));
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("theme"));
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("author"));
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("defaultSortBy"));
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("tts.voice"));
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("backup.path"));
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("translation.provider"));
    CHECK_TRUE(!SettingsService::IsSecretLikeKey("translation.baseUrl"));

    const json withSecrets = json{{"theme", "dark"},
                                  {"translation.apiKey", "sk-live-SECRET"},
                                  {"openaiToken", "tok-SECRET"},
                                  {"nested", json{{"apiKey", "sk-nested-SECRET"}}},
                                  {"tts.voice", "Huihui"}};
    CHECK_TRUE(SettingsService::ContainsSecretLikeKey(withSecrets));

    const pb::SettingsResult normalized = SettingsService::Normalize(withSecrets);
    CHECK_TRUE(normalized.HasErrors());
    CHECK_TRUE(HasIssue(normalized.issues, "secret", "translation.apiKey"));
    CHECK_TRUE(!normalized.values.contains("translation.apiKey"));
    CHECK_TRUE(!normalized.values.contains("openaiToken"));
    // 未知值内部有密钥 -> 整条丢弃，不留空壳
    CHECK_TRUE(!normalized.values.contains("nested"));
    // 正常设置不受影响
    CHECK_EQ(normalized.values.at("tts.voice").get<std::string>(), std::string("Huihui"));

    // 序列化结果 / 备份导出里没有明文 Key，也没有密钥键名
    const std::string serialized = SettingsService::Serialize(withSecrets);
    CHECK_TRUE(serialized.find("sk-live-SECRET") == std::string::npos);
    CHECK_TRUE(serialized.find("tok-SECRET") == std::string::npos);
    CHECK_TRUE(serialized.find("sk-nested-SECRET") == std::string::npos);
    const std::string serializedLower = Lower(serialized);
    CHECK_TRUE(serializedLower.find("apikey") == std::string::npos);
    CHECK_TRUE(serializedLower.find("openaitoken") == std::string::npos);

    // 通用脱敏工具：递归剔除，保留正常字段
    const json stripped = SettingsService::StripSecretLikeKeys(
        json{{"keep", 1},
             {"token", "x"},
             {"deep", json{{"password", "p"}, {"keep", 2}}}});
    CHECK_EQ(stripped.at("keep").get<int>(), 1);
    CHECK_TRUE(!stripped.contains("token"));
    CHECK_TRUE(!stripped.at("deep").contains("password"));
    CHECK_EQ(stripped.at("deep").at("keep").get<int>(), 2);
    CHECK_TRUE(!SettingsService::ContainsSecretLikeKey(stripped));

    // baseUrl：必须 http(s)；URL 里带凭证只 Warning（提示挪去 SecretStore）
    const pb::SettingsResult badUrl =
        SettingsService::Normalize(json{{"translation.baseUrl", "api.example.com/v1"}});
    CHECK_TRUE(badUrl.HasErrors());
    CHECK_TRUE(HasIssue(badUrl.issues, "url", "translation.baseUrl"));
    CHECK_EQ(badUrl.values.at("translation.baseUrl").get<std::string>(), std::string(""));

    const pb::SettingsResult credUrl = SettingsService::Normalize(
        json{{"translation.baseUrl", "https://user:pass@api.example.com/v1"}});
    CHECK_TRUE(!credUrl.HasErrors());
    CHECK_TRUE(HasIssue(credUrl.issues, "url", "translation.baseUrl"));
    CHECK_EQ(credUrl.values.at("translation.baseUrl").get<std::string>(),
             std::string("https://user:pass@api.example.com/v1"));

    const pb::SettingsResult keyInQuery = SettingsService::Normalize(
        json{{"translation.baseUrl", "https://api.example.com/v1?api_key=abc"}});
    CHECK_TRUE(HasIssue(keyInQuery.issues, "url", "translation.baseUrl"));

    const pb::SettingsResult goodUrl =
        SettingsService::Normalize(json{{"translation.baseUrl", "https://api.deepseek.com/v1"}});
    CHECK_TRUE(!goodUrl.HasErrors());
    CHECK_EQ(static_cast<int>(goodUrl.issues.size()), 0);
}

} // namespace

int main()
{
    TestSchemaAndDefaults();
    TestNormalizeBasics();
    TestLegacyMigrationAndVersion();
    TestUnknownKeyPolicies();
    TestBookSettings();
    TestValidateAndMerge();
    TestSerializeRoundTrip();
    TestSecretBoundary();
    return pb_t::Summary("settings_service_test");
}
