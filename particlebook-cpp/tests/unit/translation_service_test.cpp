// ── PB-002: TranslationService 单元测试 ──────────────────────────
// 不联网：注入 mock transport，覆盖成功、切块、错误码、取消、空段、
// 响应形状兜底、批量上限，以及「Key 不出现在任何错误 / 诊断 / 请求正文里」。
//
// 覆盖（docs/BACKLOG.md PB-002 验收）：
//   * 成功路径：请求 URL / 头 / body（model、system、targetLang、wire id）、
//     每段译文按输入顺序回填
//   * 切块：按段数、按字节数；PlanBatches；wire id 每块从 s1 重新编号
//   * 错误码：401/403 auth、402/429 quota、5xx http、400/404 provider、
//     408 timeout、坏 JSON / 缺 choices parse、transport 失败 network、超时、
//     取消 cancelled、未配置 not_configured、超限 too_large
//   * 空 / 空白段不发送；全空 -> ok 且零请求；targetLang 空 -> invalid_input
//   * 响应兜底：单段纯文本 / JSON 字符串、JSON 代码围栏、前后夹杂解释文字、
//     数字 id、缺 id 但数量一致按位置对齐；多段纯文本 -> parse 错误
//   * 隐私：Key 只出现在 Authorization 头；请求正文 / URL / 错误 / errorDetail
//     / DescribeConfig 均不含 Key；URL 里的 userinfo/query 被打码

#include "services/TranslationService.h"

#include "utils/test_assert.h"

#include <cstdio>
#include <functional>
#include <string>
#include <utility>
#include <vector>

using nlohmann::json;

namespace {

const std::string kTestKey = "sk-test-SECRET-0123456789";

struct MockReply {
    bool transportOk = true;   // false = 连接层失败，没有 HTTP 响应
    int status = 200;
    std::string body;
    std::string error;
    bool timedOut = false;
    bool cancelled = false;
};

class MockTransport {
public:
    std::function<MockReply(const pb::TranslationHttpRequest&,
                            const pb::TranslationCancelToken&)>
        handler;
    std::vector<pb::TranslationHttpRequest> requests;

    pb::TranslationHttpTransport Function()
    {
        return [this](const pb::TranslationHttpRequest& request,
                      const pb::TranslationCancelToken& cancel,
                      pb::TranslationHttpResponse& response) {
            requests.push_back(request);
            MockReply reply;
            if (handler) reply = handler(request, cancel);
            response.status = reply.status;
            response.body = reply.body;
            response.error = reply.error;
            response.timedOut = reply.timedOut;
            response.cancelled = reply.cancelled;
            return reply.transportOk;
        };
    }
};

bool Contains(const std::string& haystack, const std::string& needle)
{
    return haystack.find(needle) != std::string::npos;
}

pb::TranslationProviderConfig MakeConfig()
{
    pb::TranslationProviderConfig config;
    config.providerId = "deepseek";
    config.baseUrl = "https://api.example.com/v1";
    config.model = "deepseek-chat";
    config.apiKey = kTestKey;
    config.timeoutMs = 30000;
    return config;
}

std::vector<pb::TranslationSegment> MakeSegments(
    const std::vector<std::pair<std::string, std::string>>& items)
{
    std::vector<pb::TranslationSegment> segments;
    for (const auto& item : items) {
        pb::TranslationSegment segment;
        segment.id = item.first;
        segment.text = item.second;
        segments.push_back(std::move(segment));
    }
    return segments;
}

std::string ChatBodyWithContent(const std::string& content)
{
    json message = json::object();
    message["role"] = "assistant";
    message["content"] = content;
    json choice = json::object();
    choice["message"] = message;
    json root = json::object();
    root["choices"] = json::array({choice});
    root["model"] = "deepseek-chat";
    return root.dump();
}

std::string SegmentsContent(
    const std::vector<std::pair<std::string, std::string>>& segments)
{
    json array = json::array();
    for (const auto& item : segments) {
        json segment = json::object();
        segment["id"] = item.first;
        segment["text"] = item.second;
        array.push_back(segment);
    }
    json root = json::object();
    root["segments"] = array;
    return root.dump();
}

json UserPayload(const pb::TranslationHttpRequest& request)
{
    const json envelope = json::parse(request.body);
    const std::string user = envelope["messages"][1]["content"].get<std::string>();
    return json::parse(user);
}

// mock 按请求里的段落返回 "T:<原文>"，用来验证切块与回填顺序。
MockReply EchoTranslate(const pb::TranslationHttpRequest& request,
                        const pb::TranslationCancelToken& /*cancel*/)
{
    MockReply reply;
    const json payload = UserPayload(request);
    std::vector<std::pair<std::string, std::string>> translated;
    for (const json& segment : payload["segments"]) {
        translated.emplace_back(segment["id"].get<std::string>(),
                                "T:" + segment["text"].get<std::string>());
    }
    reply.body = ChatBodyWithContent(SegmentsContent(translated));
    return reply;
}

void CheckNoKeyInResult(const pb::TranslationResult& result)
{
    CHECK_TRUE(!Contains(result.error, kTestKey));
    CHECK_TRUE(!Contains(result.errorDetail, kTestKey));
    CHECK_TRUE(!Contains(result.error, "sk-test"));
    CHECK_TRUE(!Contains(result.errorDetail, "sk-test"));
    CHECK_TRUE(!Contains(pb::TranslationErrorCodeName(result.code), kTestKey));
}

// Key 只允许出现在 Authorization 头里；正文 / URL / 其它头都不许有。
void CheckRequestPrivacy(const pb::TranslationHttpRequest& request)
{
    CHECK_TRUE(!Contains(request.body, kTestKey));
    CHECK_TRUE(!Contains(request.url, kTestKey));
    int keyHeaders = 0;
    for (const auto& header : request.headers) {
        if (Contains(header.second, kTestKey)) {
            ++keyHeaders;
            CHECK_EQ(header.first, std::string("Authorization"));
        }
    }
    CHECK_EQ(keyHeaders, 1);
}

// ── 配置 ─────────────────────────────────────────────────────────

void TestConfigFromSettings()
{
    json settings = json::object();
    settings["translation.provider"] = "deepseek";
    settings["translation.baseUrl"] = "https://api.deepseek.com/v1";
    settings["translation.model"] = "deepseek-chat";
    settings["translation.timeoutSeconds"] = 45;

    const pb::TranslationProviderConfig config =
        pb::TranslationService::ConfigFromSettings(settings);
    CHECK_EQ(config.providerId, std::string("deepseek"));
    CHECK_EQ(config.baseUrl, std::string("https://api.deepseek.com/v1"));
    CHECK_EQ(config.model, std::string("deepseek-chat"));
    CHECK_EQ(config.timeoutMs, 45000);
    CHECK_TRUE(config.apiKey.empty());  // Key 不来自 settings

    // 空 settings：用 PB-023 schema 的默认值（provider=openai / 空 URL / 60s）
    const pb::TranslationProviderConfig defaults =
        pb::TranslationService::ConfigFromSettings(json::object());
    CHECK_EQ(defaults.providerId, std::string("openai"));
    CHECK_EQ(defaults.baseUrl, std::string(""));
    CHECK_EQ(defaults.model, std::string(""));
    CHECK_EQ(defaults.timeoutMs, 60000);
    CHECK_TRUE(defaults.apiKey.empty());

    // 越界 / 非法 timeout 回落到 60s
    json badTimeout = json::object();
    badTimeout["translation.timeoutSeconds"] = 9999;
    CHECK_EQ(pb::TranslationService::ConfigFromSettings(badTimeout).timeoutMs, 60000);
    badTimeout["translation.timeoutSeconds"] = 1;
    CHECK_EQ(pb::TranslationService::ConfigFromSettings(badTimeout).timeoutMs, 60000);
}

// ── 成功路径 / 请求内容 ─────────────────────────────────────────

void TestSuccessRequestShape()
{
    MockTransport mock;
    mock.handler = EchoTranslate;
    pb::TranslationService service(mock.Function());

    const auto segments = MakeSegments({{"p1", "Hello"}, {"p2", "World"}});
    const pb::TranslationResult result =
        service.TranslateBatch(MakeConfig(), segments, "en", "zh", "",
                               pb::TranslationCancelToken());

    CHECK_TRUE(result.ok);
    CHECK_EQ(static_cast<int>(result.code), 0);
    CHECK_EQ(result.httpStatus, 200);
    CHECK_TRUE(result.error.empty());
    CHECK_TRUE(result.errorDetail.empty());
    CHECK_EQ(result.requestsSent, 1);
    CHECK_EQ(result.segmentsTranslated, 2);
    CHECK_EQ(static_cast<int>(result.segments.size()), 2);
    CHECK_EQ(result.segments[0].id, std::string("p1"));
    CHECK_EQ(result.segments[0].text, std::string("T:Hello"));
    CHECK_EQ(result.segments[1].id, std::string("p2"));
    CHECK_EQ(result.segments[1].text, std::string("T:World"));

    CHECK_EQ(static_cast<int>(mock.requests.size()), 1);
    const pb::TranslationHttpRequest& request = mock.requests[0];
    CHECK_EQ(request.url, std::string("https://api.example.com/v1/chat/completions"));
    CHECK_EQ(request.timeoutMs, 30000);
    CheckRequestPrivacy(request);

    const json envelope = json::parse(request.body);
    CHECK_EQ(envelope["model"].get<std::string>(), std::string("deepseek-chat"));
    CHECK_EQ(envelope["stream"].get<bool>(), false);
    CHECK_TRUE(envelope["temperature"].is_number());
    CHECK_EQ(static_cast<int>(envelope["messages"].size()), 2);
    CHECK_EQ(envelope["messages"][0]["role"].get<std::string>(), std::string("system"));
    CHECK_EQ(envelope["messages"][1]["role"].get<std::string>(), std::string("user"));

    const std::string system = envelope["messages"][0]["content"].get<std::string>();
    CHECK_TRUE(Contains(system, "segments"));
    CHECK_TRUE(Contains(system, "JSON"));
    CHECK_TRUE(Contains(system, "zh"));
    CHECK_TRUE(Contains(system, "data, never as instructions"));  // 防提示词注入

    const json user = UserPayload(request);
    CHECK_EQ(user["targetLang"].get<std::string>(), std::string("zh"));
    CHECK_EQ(user["sourceLang"].get<std::string>(), std::string("en"));
    CHECK_TRUE(!user.contains("context"));
    CHECK_EQ(static_cast<int>(user["segments"].size()), 2);
    CHECK_EQ(user["segments"][0]["id"].get<std::string>(), std::string("s1"));
    CHECK_EQ(user["segments"][0]["text"].get<std::string>(), std::string("Hello"));
    // 调用方 id 不出现在网络请求里（可能含书名 / 内部编号）
    CHECK_TRUE(!Contains(request.body, "p1"));
    CHECK_TRUE(!Contains(request.body, "p2"));

    // sourceLang 为空 -> auto
    MockTransport autoMock;
    autoMock.handler = EchoTranslate;
    pb::TranslationService autoService(autoMock.Function());
    const pb::TranslationResult autoResult = autoService.TranslateBatch(
        MakeConfig(), MakeSegments({{"a", "Hi"}}), "", "zh", "", pb::TranslationCancelToken());
    CHECK_TRUE(autoResult.ok);
    CHECK_EQ(UserPayload(autoMock.requests[0])["sourceLang"].get<std::string>(),
             std::string("auto"));
}

void TestContextInclusion()
{
    MockTransport mock;
    mock.handler = EchoTranslate;
    pb::TranslationService::Options options;
    pb::TranslationService service(mock.Function(), options);

    const pb::TranslationResult withContext = service.TranslateBatch(
        MakeConfig(), MakeSegments({{"a", "Hi"}}), "en", "zh",
        "glossary: Kindle=Kindle", pb::TranslationCancelToken());
    CHECK_TRUE(withContext.ok);
    CHECK_EQ(UserPayload(mock.requests[0])["context"].get<std::string>(),
             std::string("glossary: Kindle=Kindle"));

    mock.requests.clear();
    options.includeContext = false;
    pb::TranslationService withoutContext(mock.Function(), options);
    const pb::TranslationResult result = withoutContext.TranslateBatch(
        MakeConfig(), MakeSegments({{"a", "Hi"}}), "en", "zh", "secret context",
        pb::TranslationCancelToken());
    CHECK_TRUE(result.ok);
    CHECK_TRUE(!UserPayload(mock.requests[0]).contains("context"));
    CHECK_TRUE(!Contains(mock.requests[0].body, "secret context"));
}

void TestBaseUrlVariants()
{
    MockTransport mock;
    mock.handler = EchoTranslate;
    pb::TranslationService service(mock.Function());

    pb::TranslationProviderConfig trailing = MakeConfig();
    trailing.baseUrl = "https://api.example.com/v1///";
    CHECK_TRUE(service.TranslateBatch(trailing, MakeSegments({{"a", "Hi"}}), "en", "zh", "",
                                      pb::TranslationCancelToken())
                   .ok);
    CHECK_EQ(mock.requests[0].url,
             std::string("https://api.example.com/v1/chat/completions"));

    mock.requests.clear();
    pb::TranslationProviderConfig full = MakeConfig();
    full.baseUrl = "https://api.example.com/v1/chat/completions";
    CHECK_TRUE(service.TranslateBatch(full, MakeSegments({{"a", "Hi"}}), "en", "zh", "",
                                      pb::TranslationCancelToken())
                   .ok);
    CHECK_EQ(mock.requests[0].url,
             std::string("https://api.example.com/v1/chat/completions"));

    // 非 http(s) / 缺字段：NotConfigured，且一个请求都不发
    mock.requests.clear();
    pb::TranslationProviderConfig ftp = MakeConfig();
    ftp.baseUrl = "ftp://api.example.com/v1";
    const pb::TranslationResult ftpResult = service.TranslateBatch(
        ftp, MakeSegments({{"a", "Hi"}}), "en", "zh", "", pb::TranslationCancelToken());
    CHECK_TRUE(!ftpResult.ok);
    CHECK_TRUE(ftpResult.code == pb::TranslationErrorCode::NotConfigured);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    pb::TranslationProviderConfig noKey = MakeConfig();
    noKey.apiKey.clear();
    const pb::TranslationResult noKeyResult = service.TranslateBatch(
        noKey, MakeSegments({{"a", "Hi"}}), "en", "zh", "", pb::TranslationCancelToken());
    CHECK_TRUE(noKeyResult.code == pb::TranslationErrorCode::NotConfigured);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    pb::TranslationProviderConfig noModel = MakeConfig();
    noModel.model.clear();
    CHECK_TRUE(service.TranslateBatch(noModel, MakeSegments({{"a", "Hi"}}), "en", "zh", "",
                                      pb::TranslationCancelToken())
                   .code
               == pb::TranslationErrorCode::NotConfigured);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    // URL 里带 Key / 凭证：拒绝发送（只允许 Authorization 头携带 Key）
    pb::TranslationProviderConfig urlKey = MakeConfig();
    urlKey.baseUrl = "https://api.example.com/v1?api_key=abc123";
    const pb::TranslationResult urlKeyResult = service.TranslateBatch(
        urlKey, MakeSegments({{"a", "Hi"}}), "en", "zh", "", pb::TranslationCancelToken());
    CHECK_TRUE(urlKeyResult.code == pb::TranslationErrorCode::NotConfigured);
    CHECK_TRUE(!Contains(urlKeyResult.errorDetail, "abc123"));
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    pb::TranslationProviderConfig urlUserInfo = MakeConfig();
    urlUserInfo.baseUrl = "https://user:pass@api.example.com/v1";
    const pb::TranslationResult urlUserInfoResult = service.TranslateBatch(
        urlUserInfo, MakeSegments({{"a", "Hi"}}), "en", "zh", "",
        pb::TranslationCancelToken());
    CHECK_TRUE(urlUserInfoResult.code == pb::TranslationErrorCode::NotConfigured);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    // Key 里的换行不能进 HTTP 头（CRLF 注入）；首尾空白会被裁掉
    pb::TranslationProviderConfig injected = MakeConfig();
    injected.apiKey = "sk-abc\r\nX-Evil: 1";
    const pb::TranslationResult injectedResult = service.TranslateBatch(
        injected, MakeSegments({{"a", "Hi"}}), "en", "zh", "",
        pb::TranslationCancelToken());
    CHECK_TRUE(injectedResult.code == pb::TranslationErrorCode::NotConfigured);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    mock.requests.clear();
    pb::TranslationProviderConfig padded = MakeConfig();
    padded.apiKey = "   " + kTestKey + "  ";
    const pb::TranslationResult paddedResult = service.TranslateBatch(
        padded, MakeSegments({{"a", "Hi"}}), "en", "zh", "",
        pb::TranslationCancelToken());
    CHECK_TRUE(paddedResult.ok);
    bool foundAuth = false;
    for (const auto& header : mock.requests[0].headers) {
        if (header.first == "Authorization") {
            foundAuth = true;
            CHECK_EQ(header.second, std::string("Bearer ") + kTestKey);
        }
    }
    CHECK_TRUE(foundAuth);
}

// ── 切块 ────────────────────────────────────────────────────────

void TestChunkingBySegments()
{
    MockTransport mock;
    mock.handler = EchoTranslate;
    pb::TranslationService::Options options;
    options.maxSegmentsPerRequest = 2;
    pb::TranslationService service(mock.Function(), options);

    const auto segments = MakeSegments(
        {{"p1", "one"}, {"p2", "two"}, {"p3", "three"}, {"p4", "four"}, {"p5", "five"}});
    const pb::TranslationResult result =
        service.TranslateBatch(MakeConfig(), segments, "en", "zh", "",
                               pb::TranslationCancelToken());

    CHECK_TRUE(result.ok);
    CHECK_EQ(result.requestsSent, 3);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 3);
    CHECK_EQ(static_cast<int>(UserPayload(mock.requests[0])["segments"].size()), 2);
    CHECK_EQ(static_cast<int>(UserPayload(mock.requests[1])["segments"].size()), 2);
    CHECK_EQ(static_cast<int>(UserPayload(mock.requests[2])["segments"].size()), 1);
    // 每块 wire id 都从 s1 重新开始
    CHECK_EQ(UserPayload(mock.requests[0])["segments"][0]["id"].get<std::string>(),
             std::string("s1"));
    CHECK_EQ(UserPayload(mock.requests[1])["segments"][0]["id"].get<std::string>(),
             std::string("s1"));
    CHECK_EQ(result.segmentsTranslated, 5);
    for (std::size_t i = 0; i < segments.size(); ++i) {
        CHECK_EQ(result.segments[i].id, segments[i].id);
        CHECK_EQ(result.segments[i].text, "T:" + segments[i].text);
    }

    const std::vector<std::vector<std::size_t>> plan = service.PlanBatches(segments);
    CHECK_EQ(static_cast<int>(plan.size()), 3);
    CHECK_EQ(static_cast<int>(plan[0].size()), 2);
    CHECK_EQ(static_cast<int>(plan[2].size()), 1);
    CHECK_EQ(static_cast<int>(plan[2][0]), 4);
}

void TestChunkingByChars()
{
    MockTransport mock;
    mock.handler = EchoTranslate;
    pb::TranslationService::Options options;
    options.maxSegmentsPerRequest = 100;
    options.maxCharsPerRequest = 10;
    pb::TranslationService service(mock.Function(), options);

    // 10 + 10 + 5 字节：每段都装不下第二段，各占一块
    const auto segments = MakeSegments(
        {{"a", "1234567890"}, {"b", "abcdefghij"}, {"c", "abcde"}});
    const pb::TranslationResult result =
        service.TranslateBatch(MakeConfig(), segments, "en", "zh", "",
                               pb::TranslationCancelToken());
    CHECK_TRUE(result.ok);
    CHECK_EQ(result.requestsSent, 3);
    CHECK_EQ(result.segmentsTranslated, 3);

    // 5 + 5 + 5：前两段合成一块（恰好 10），第三段单独一块
    mock.requests.clear();
    const auto small = MakeSegments({{"a", "12345"}, {"b", "67890"}, {"c", "abcde"}});
    const pb::TranslationResult smallResult =
        service.TranslateBatch(MakeConfig(), small, "en", "zh", "",
                               pb::TranslationCancelToken());
    CHECK_TRUE(smallResult.ok);
    CHECK_EQ(smallResult.requestsSent, 2);
    CHECK_EQ(static_cast<int>(UserPayload(mock.requests[0])["segments"].size()), 2);
    CHECK_EQ(static_cast<int>(UserPayload(mock.requests[1])["segments"].size()), 1);
}

// ── 空段 / 上限 ─────────────────────────────────────────────────

void TestEmptyAndBlankSegments()
{
    MockTransport mock;
    mock.handler = EchoTranslate;
    pb::TranslationService service(mock.Function());

    // 完全没有段：ok，零请求（即使配置不完整）
    pb::TranslationProviderConfig unconfigured = MakeConfig();
    unconfigured.apiKey.clear();
    const pb::TranslationResult empty = service.TranslateBatch(
        unconfigured, {}, "en", "zh", "", pb::TranslationCancelToken());
    CHECK_TRUE(empty.ok);
    CHECK_EQ(empty.requestsSent, 0);
    CHECK_EQ(static_cast<int>(empty.segments.size()), 0);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    // 空 / 空白段不发送，结果里保留空译文
    const auto segments = MakeSegments({{"a", ""}, {"b", "   \n\t "}, {"c", "Hi"}});
    const pb::TranslationResult result =
        service.TranslateBatch(MakeConfig(), segments, "en", "zh", "",
                               pb::TranslationCancelToken());
    CHECK_TRUE(result.ok);
    CHECK_EQ(result.requestsSent, 1);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 1);
    CHECK_EQ(static_cast<int>(UserPayload(mock.requests[0])["segments"].size()), 1);
    CHECK_EQ(UserPayload(mock.requests[0])["segments"][0]["text"].get<std::string>(),
             std::string("Hi"));
    CHECK_EQ(static_cast<int>(result.segments.size()), 3);
    CHECK_TRUE(result.segments[0].text.empty());
    CHECK_TRUE(result.segments[1].text.empty());
    CHECK_EQ(result.segments[2].text, std::string("T:Hi"));
    CHECK_EQ(result.segmentsTranslated, 1);

    // targetLang 为空 -> invalid_input，且不发请求
    mock.requests.clear();
    const pb::TranslationResult badTarget = service.TranslateBatch(
        MakeConfig(), MakeSegments({{"a", "Hi"}}), "en", "  ", "",
        pb::TranslationCancelToken());
    CHECK_TRUE(!badTarget.ok);
    CHECK_TRUE(badTarget.code == pb::TranslationErrorCode::InvalidInput);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);
}

void TestLimits()
{
    MockTransport mock;
    mock.handler = EchoTranslate;
    pb::TranslationService::Options options;
    options.maxSegmentsPerCall = 2;
    pb::TranslationService service(mock.Function(), options);

    // 单次调用段数上限
    const pb::TranslationResult tooMany = service.TranslateBatch(
        MakeConfig(), MakeSegments({{"a", "1"}, {"b", "2"}, {"c", "3"}}), "en", "zh", "",
        pb::TranslationCancelToken());
    CHECK_TRUE(!tooMany.ok);
    CHECK_TRUE(tooMany.code == pb::TranslationErrorCode::TooLarge);
    CHECK_TRUE(Contains(tooMany.error, "too many segments"));
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    // 单段字节上限（默认 8000）
    MockTransport defaultMock;
    defaultMock.handler = EchoTranslate;
    pb::TranslationService defaultService(defaultMock.Function());
    const std::string huge(8001, 'x');
    const pb::TranslationResult tooLong = defaultService.TranslateBatch(
        MakeConfig(), MakeSegments({{"big", huge}}), "en", "zh", "",
        pb::TranslationCancelToken());
    CHECK_TRUE(!tooLong.ok);
    CHECK_TRUE(tooLong.code == pb::TranslationErrorCode::TooLarge);
    CHECK_TRUE(Contains(tooLong.error, "segment #1"));
    CHECK_EQ(static_cast<int>(defaultMock.requests.size()), 0);
}

// ── HTTP / 传输错误 ─────────────────────────────────────────────

void TestHttpErrors()
{
    MockTransport mock;
    pb::TranslationService service(mock.Function());
    const auto segments = MakeSegments({{"a", "Hello"}});

    const auto run = [&](MockReply reply) {
        mock.requests.clear();
        mock.handler = [reply](const pb::TranslationHttpRequest&,
                               const pb::TranslationCancelToken&) { return reply; };
        return service.TranslateBatch(MakeConfig(), segments, "en", "zh", "",
                                      pb::TranslationCancelToken());
    };

    // 401：Key 被拒；provider 错误信息里回显 Key 也必须被脱敏
    MockReply auth;
    auth.status = 401;
    auth.body = "{\"error\":{\"message\":\"Invalid key " + kTestKey + "\"}}";
    const pb::TranslationResult authResult = run(auth);
    CHECK_TRUE(!authResult.ok);
    CHECK_TRUE(authResult.code == pb::TranslationErrorCode::Auth);
    CHECK_EQ(authResult.httpStatus, 401);
    CHECK_TRUE(!authResult.error.empty());
    CHECK_TRUE(!authResult.errorDetail.empty());
    CheckNoKeyInResult(authResult);
    CHECK_TRUE(Contains(authResult.errorDetail, "Invalid key"));

    // 403 -> auth
    MockReply forbidden;
    forbidden.status = 403;
    CHECK_TRUE(run(forbidden).code == pb::TranslationErrorCode::Auth);

    // 429 -> quota
    MockReply rateLimited;
    rateLimited.status = 429;
    rateLimited.body = "{\"error\":{\"message\":\"rate limit exceeded\"}}";
    const pb::TranslationResult quota = run(rateLimited);
    CHECK_TRUE(quota.code == pb::TranslationErrorCode::Quota);
    CHECK_TRUE(Contains(quota.errorDetail, "rate limit"));

    // 500 -> http
    MockReply serverError;
    serverError.status = 500;
    serverError.body = "{\"error\":{\"message\":\"upstream exploded\"}}";
    const pb::TranslationResult http = run(serverError);
    CHECK_TRUE(http.code == pb::TranslationErrorCode::Http);
    CHECK_TRUE(Contains(http.errorDetail, "upstream exploded"));

    // 404 / 400 -> provider（模型不存在之类）
    MockReply notFound;
    notFound.status = 404;
    notFound.body = "{\"error\":{\"message\":\"model not found\"}}";
    const pb::TranslationResult provider = run(notFound);
    CHECK_TRUE(provider.code == pb::TranslationErrorCode::Provider);
    CHECK_TRUE(Contains(provider.errorDetail, "model not found"));

    MockReply badRequest;
    badRequest.status = 400;
    badRequest.body = "bad request: unsupported model";
    const pb::TranslationResult bad = run(badRequest);
    CHECK_TRUE(bad.code == pb::TranslationErrorCode::Provider);
    CHECK_TRUE(Contains(bad.errorDetail, "unsupported model"));

    // 408 -> timeout
    MockReply requestTimeout;
    requestTimeout.status = 408;
    CHECK_TRUE(run(requestTimeout).code == pb::TranslationErrorCode::Timeout);

    // 200 但响应不是 JSON -> parse
    MockReply html;
    html.body = "<html>not json</html>";
    const pb::TranslationResult parse = run(html);
    CHECK_TRUE(parse.code == pb::TranslationErrorCode::Parse);
    CheckNoKeyInResult(parse);

    // 200 但没有 choices -> parse
    MockReply noChoices;
    noChoices.body = "{\"error\":\"unexpected\"}";
    CHECK_TRUE(run(noChoices).code == pb::TranslationErrorCode::Parse);

    // 成功路径也不许出现 Key
    MockReply success;
    success.body = ChatBodyWithContent(SegmentsContent({{"s1", "你好"}}));
    CheckNoKeyInResult(run(success));
}

void TestTransportFailures()
{
    MockTransport mock;
    pb::TranslationService service(mock.Function());
    const auto segments = MakeSegments({{"a", "Hello"}});

    const auto run = [&](MockReply reply) {
        mock.requests.clear();
        mock.handler = [reply](const pb::TranslationHttpRequest&,
                               const pb::TranslationCancelToken&) { return reply; };
        return service.TranslateBatch(MakeConfig(), segments, "en", "zh", "",
                                      pb::TranslationCancelToken());
    };

    // 连接失败 -> network
    MockReply network;
    network.transportOk = false;
    network.error = "connect failed (WinHTTP error 12029)";
    const pb::TranslationResult networkResult = run(network);
    CHECK_TRUE(!networkResult.ok);
    CHECK_TRUE(networkResult.code == pb::TranslationErrorCode::Network);
    CHECK_TRUE(Contains(networkResult.errorDetail, "12029"));

    // 超时 -> timeout
    MockReply timeout;
    timeout.transportOk = false;
    timeout.timedOut = true;
    timeout.error = "receive timed out";
    const pb::TranslationResult timeoutResult = run(timeout);
    CHECK_TRUE(timeoutResult.code == pb::TranslationErrorCode::Timeout);
    CHECK_TRUE(Contains(timeoutResult.errorDetail, "timed out"));

    // transport 错误里带 Key -> 必须脱敏
    MockReply leaky;
    leaky.transportOk = false;
    leaky.error = "TLS failed for bearer " + kTestKey;
    const pb::TranslationResult leakyResult = run(leaky);
    CHECK_TRUE(leakyResult.code == pb::TranslationErrorCode::Network);
    CheckNoKeyInResult(leakyResult);
    CHECK_TRUE(!Contains(leakyResult.errorDetail, "bearer " + kTestKey));

    // transport 直接报 cancelled
    MockReply cancelled;
    cancelled.transportOk = false;
    cancelled.cancelled = true;
    CHECK_TRUE(run(cancelled).code == pb::TranslationErrorCode::Cancelled);

    // 拿到响应但标记 cancelled
    MockReply cancelledWithResponse;
    cancelledWithResponse.cancelled = true;
    cancelledWithResponse.status = 200;
    CHECK_TRUE(run(cancelledWithResponse).code == pb::TranslationErrorCode::Cancelled);
}

// ── 响应形状兜底 ────────────────────────────────────────────────

void TestResponseShapeFallbacks()
{
    MockTransport mock;
    pb::TranslationService service(mock.Function());

    const auto run = [&](const std::string& content,
                         const std::vector<pb::TranslationSegment>& segments) {
        mock.requests.clear();
        mock.handler = [content](const pb::TranslationHttpRequest&,
                                 const pb::TranslationCancelToken&) {
            MockReply reply;
            reply.body = ChatBodyWithContent(content);
            return reply;
        };
        return service.TranslateBatch(MakeConfig(), segments, "en", "zh", "",
                                      pb::TranslationCancelToken());
    };

    // 单段：模型直接返回纯文本
    const pb::TranslationResult plain =
        run("你好", MakeSegments({{"a", "Hello"}}));
    CHECK_TRUE(plain.ok);
    CHECK_EQ(plain.segments[0].text, std::string("你好"));

    // 单段：JSON 字符串形式
    const pb::TranslationResult quoted =
        run(json("你好").dump(), MakeSegments({{"a", "Hello"}}));
    CHECK_TRUE(quoted.ok);
    CHECK_EQ(quoted.segments[0].text, std::string("你好"));

    // JSON 代码围栏 + 前后解释文字：提取花括号内容
    const std::string fenced =
        "Here is the translation:\n\n```json\n"
        + SegmentsContent({{"s1", "你好"}, {"s2", "世界"}}) + "\n```\n";
    const pb::TranslationResult fencedResult =
        run(fenced, MakeSegments({{"a", "Hello"}, {"b", "World"}}));
    CHECK_TRUE(fencedResult.ok);
    CHECK_EQ(fencedResult.segments[0].text, std::string("你好"));
    CHECK_EQ(fencedResult.segments[1].text, std::string("世界"));

    // 缺 id 但数量一致：按位置对齐
    const pb::TranslationResult positional =
        run(SegmentsContent({{"x", "甲"}, {"y", "乙"}}),
            MakeSegments({{"a", "A"}, {"b", "B"}}));
    CHECK_TRUE(positional.ok);
    CHECK_EQ(positional.segments[0].text, std::string("甲"));
    CHECK_EQ(positional.segments[1].text, std::string("乙"));

    // 数字 id：1 对应 wire id s1
    json numericRoot = json::object();
    json numericSegments = json::array();
    json first = json::object();
    first["id"] = 1;
    first["text"] = "一";
    json second = json::object();
    second["id"] = 2;
    second["text"] = "二";
    numericSegments.push_back(first);
    numericSegments.push_back(second);
    numericRoot["segments"] = numericSegments;
    const pb::TranslationResult numeric =
        run(numericRoot.dump(), MakeSegments({{"a", "One"}, {"b", "Two"}}));
    CHECK_TRUE(numeric.ok);
    CHECK_EQ(numeric.segments[0].text, std::string("一"));

    // 多段却返回纯文本 -> parse
    const pb::TranslationResult multiPlain =
        run("just a sentence", MakeSegments({{"a", "A"}, {"b", "B"}}));
    CHECK_TRUE(multiPlain.code == pb::TranslationErrorCode::Parse);

    // 数量对不上 -> parse
    const pb::TranslationResult missing =
        run(SegmentsContent({{"s1", "only one"}}),
            MakeSegments({{"a", "A"}, {"b", "B"}}));
    CHECK_TRUE(missing.code == pb::TranslationErrorCode::Parse);

    // 译文为空 -> parse
    const pb::TranslationResult blankTranslation =
        run(SegmentsContent({{"s1", ""}}), MakeSegments({{"a", "A"}}));
    CHECK_TRUE(blankTranslation.code == pb::TranslationErrorCode::Parse);
}

// ── 取消 ────────────────────────────────────────────────────────

void TestCancellation()
{
    MockTransport mock;
    mock.handler = EchoTranslate;
    const auto segments = MakeSegments({{"a", "Hello"}, {"b", "World"}});

    // 调用前已取消：一个请求都不发
    pb::TranslationService service(mock.Function());
    pb::TranslationCancelToken token;
    token.Cancel();
    const pb::TranslationResult preCancelled = service.TranslateBatch(
        MakeConfig(), segments, "en", "zh", "", token);
    CHECK_TRUE(!preCancelled.ok);
    CHECK_TRUE(preCancelled.code == pb::TranslationErrorCode::Cancelled);
    CHECK_EQ(static_cast<int>(mock.requests.size()), 0);

    // 第一块成功后取消：第二块不再发送，保留已翻译的部分
    MockTransport chunkMock;
    pb::TranslationCancelToken midToken;
    chunkMock.handler = [&midToken](const pb::TranslationHttpRequest& request,
                                    const pb::TranslationCancelToken& cancel) {
        midToken.Cancel();
        return EchoTranslate(request, cancel);
    };
    pb::TranslationService::Options options;
    options.maxSegmentsPerRequest = 1;
    pb::TranslationService chunkService(chunkMock.Function(), options);
    const pb::TranslationResult partial = chunkService.TranslateBatch(
        MakeConfig(), segments, "en", "zh", "", midToken);
    CHECK_TRUE(!partial.ok);
    CHECK_TRUE(partial.code == pb::TranslationErrorCode::Cancelled);
    CHECK_EQ(partial.requestsSent, 1);
    CHECK_EQ(static_cast<int>(chunkMock.requests.size()), 1);
    CHECK_EQ(partial.segmentsTranslated, 1);
    CHECK_EQ(partial.segments[0].text, std::string("T:Hello"));
    CHECK_TRUE(partial.segments[1].text.empty());
}

// ── 隐私 / 诊断 ─────────────────────────────────────────────────

void TestPrivacyAndDiagnostics()
{
    // 诊断摘要不含 Key；baseUrl 里的 userinfo / query 打码
    pb::TranslationProviderConfig config = MakeConfig();
    config.baseUrl = "https://user:pass@api.example.com/v1?api_key=abc123";
    const std::string summary = pb::TranslationService::DescribeConfig(config);
    CHECK_TRUE(!Contains(summary, kTestKey));
    CHECK_TRUE(!Contains(summary, "pass"));
    CHECK_TRUE(!Contains(summary, "abc123"));
    CHECK_TRUE(Contains(summary, "***"));
    CHECK_TRUE(Contains(summary, "apiKey=(set)"));
    CHECK_TRUE(Contains(summary, "deepseek-chat"));

    // 通用脱敏：Bearer / sk-xxx / 截断
    const std::string redacted = pb::TranslationService::RedactSecret(
        "request failed: Authorization: Bearer " + kTestKey + " and key sk-abcdefgh1234", "");
    CHECK_TRUE(!Contains(redacted, kTestKey));
    CHECK_TRUE(!Contains(redacted, "abcdefgh1234"));
    CHECK_TRUE(Contains(redacted, "***"));
    const std::string longText = pb::TranslationService::RedactSecret(std::string(2000, 'z'), "");
    CHECK_TRUE(longText.size() <= 403);

    // 成功路径：所有请求正文 / URL 检查一次
    MockTransport mock;
    mock.handler = EchoTranslate;
    pb::TranslationService service(mock.Function());
    const pb::TranslationResult ok = service.TranslateBatch(
        MakeConfig(), MakeSegments({{"a", "Hello"}}), "en", "zh", "",
        pb::TranslationCancelToken());
    CHECK_TRUE(ok.ok);
    CheckNoKeyInResult(ok);
    for (const pb::TranslationHttpRequest& request : mock.requests) {
        CheckRequestPrivacy(request);
    }
    // Authorization 头恰好出现一次，且是 "Bearer <key>"
    const std::string expectedAuth = "Bearer " + kTestKey;
    int authHeaderCount = 0;
    for (const auto& header : mock.requests[0].headers) {
        if (header.first == "Authorization") {
            ++authHeaderCount;
            CHECK_EQ(header.second, expectedAuth);
        }
    }
    CHECK_EQ(authHeaderCount, 1);
    CHECK_TRUE(!Contains(pb::TranslationService::DescribeConfig(MakeConfig()), kTestKey));
}

void TestErrorCodeNames()
{
    CHECK_EQ(std::string(pb::TranslationErrorCodeName(pb::TranslationErrorCode::None)),
             std::string("none"));
    CHECK_EQ(std::string(pb::TranslationErrorCodeName(pb::TranslationErrorCode::Auth)),
             std::string("auth"));
    CHECK_EQ(std::string(pb::TranslationErrorCodeName(pb::TranslationErrorCode::Quota)),
             std::string("quota"));
    CHECK_EQ(std::string(pb::TranslationErrorCodeName(pb::TranslationErrorCode::Parse)),
             std::string("parse"));
    CHECK_EQ(std::string(pb::TranslationErrorCodeName(pb::TranslationErrorCode::Network)),
             std::string("network"));
    CHECK_EQ(std::string(pb::TranslationErrorCodeName(pb::TranslationErrorCode::Timeout)),
             std::string("timeout"));
    CHECK_EQ(std::string(pb::TranslationErrorCodeName(pb::TranslationErrorCode::Cancelled)),
             std::string("cancelled"));
    CHECK_EQ(std::string(pb::TranslationErrorCodeName(pb::TranslationErrorCode::TooLarge)),
             std::string("too_large"));
}

} // namespace

int main()
{
    TestConfigFromSettings();
    TestSuccessRequestShape();
    TestContextInclusion();
    TestBaseUrlVariants();
    TestChunkingBySegments();
    TestChunkingByChars();
    TestEmptyAndBlankSegments();
    TestLimits();
    TestHttpErrors();
    TestTransportFailures();
    TestResponseShapeFallbacks();
    TestCancellation();
    TestPrivacyAndDiagnostics();
    TestErrorCodeNames();
    return pb_t::Summary("translation_service_test");
}
