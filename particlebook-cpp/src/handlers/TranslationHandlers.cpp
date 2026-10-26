#include "TranslationHandlers.h"

#include "BridgeServer.h"
#include "services/DatabaseService.h"
#include "services/TranslationService.h"
#include "platform/JobService.h"
#include "platform/LogService.h"
#include "platform/SecretStore.h"
#include "platform/SettingsService.h"

#include <memory>
#include <string>
#include <vector>

namespace {

json TranslationError(const std::string& code, const std::string& error)
{
    return json{
        {"ok", false},
        {"code", code},
        {"error", error},
        {"segments", json::array()},
        {"httpStatus", 0},
        {"requestsSent", 0},
        {"segmentsTranslated", 0},
        {"elapsedMs", 0},
    };
}

} // namespace

void RegisterTranslationHandlers(BridgeServer* bridge, DatabaseService* db,
                                 pb::JobService* jobs, SecretStore* secrets,
                                 pb::TranslationService* translation)
{
    bridge->RegisterMethod("translation:setKey", [secrets](const json& p) -> json {
        if (!secrets) return json(false);
        const std::string key = p.value("key", "");
        if (key.empty() || key.size() > 16384) return json(false);
        return json(secrets->Set(pb::kTranslationApiKeySecretName, key));
    });

    bridge->RegisterMethod("translation:hasKey", [secrets](const json&) -> json {
        return json(secrets ? secrets->Has(pb::kTranslationApiKeySecretName) : false);
    });

    bridge->RegisterMethod("translation:clearKey", [secrets](const json&) -> json {
        if (!secrets) return json(false);
        secrets->Remove(pb::kTranslationApiKeySecretName);
        return json(true);
    });

    bridge->RegisterAsyncMethod("translation:translate",
        [db, jobs, secrets, translation](const json& p,
                                         std::function<void(json)> respond,
                                         std::function<void(std::string)> reject) {
            if (!jobs || !db || !secrets || !translation) {
                reject("translation_unavailable");
                return;
            }
            if (!db->GetSettings().value("translation.enabled", false)) {
                reject("translation_disabled");
                return;
            }

            const json rawSegments = p.value("segments", json::array());
            if (!rawSegments.is_array() || rawSegments.empty()) {
                reject("invalid_segments");
                return;
            }
            std::vector<pb::TranslationSegment> segments;
            segments.reserve(rawSegments.size());
            for (const auto& s : rawSegments) {
                pb::TranslationSegment seg;
                seg.id = s.value("id", "");
                seg.text = s.value("text", "");
                if (seg.id.empty() || seg.text.empty()) continue;
                segments.push_back(std::move(seg));
            }
            if (segments.empty()) { reject("empty_text"); return; }

            auto config = pb::TranslationService::ConfigFromSettings(
                pb::SettingsService::Normalize(db->GetSettings()).values);
            if (config.baseUrl.empty() || config.model.empty()) {
                reject("translation_not_configured");
                return;
            }
            if (!secrets->Get(pb::kTranslationApiKeySecretName, config.apiKey)) {
                reject("missing_api_key");
                return;
            }

            const std::string sourceLang = p.value("sourceLang", "auto");
            const std::string targetLang = p.value("targetLang", "zh");
            const std::string context = p.value("context", "");
            pb::LogInfo("translation", "request " + pb::TranslationService::DescribeConfig(config));

            auto out = std::make_shared<json>(TranslationError("translation_failed", "translation did not finish"));
            const pb::JobId id = jobs->Submit(
                "translation:translate",
                [translation, config, segments, sourceLang, targetLang, context, out](pb::JobContext&) {
                    const auto result = translation->TranslateBatch(
                        config, segments, sourceLang, targetLang, context);
                    json reply;
                    reply["ok"] = result.ok;
                    reply["code"] = pb::TranslationErrorCodeName(result.code);
                    reply["error"] = result.error;
                    reply["httpStatus"] = result.httpStatus;
                    reply["requestsSent"] = result.requestsSent;
                    reply["segmentsTranslated"] = result.segmentsTranslated;
                    reply["elapsedMs"] = result.elapsedMs;
                    reply["segments"] = json::array();
                    for (const auto& seg : result.segments) {
                        reply["segments"].push_back({{"id", seg.id}, {"text", seg.text}});
                    }
                    *out = std::move(reply);
                },
                nullptr,
                [out, respond, reject](const pb::JobResult& job) {
                    if (!job.ok) { reject(job.cancelled ? "cancelled" : job.error); return; }
                    if (!out->value("ok", false)) {
                        reject(out->value("code", std::string("translation_failed")));
                        return;
                    }
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) reject("job_service_unavailable");
        });
}
