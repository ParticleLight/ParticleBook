#include "TtsHandlers.h"
#include "BridgeServer.h"
#include "platform/SapiTts.h"

void RegisterTtsHandlers(BridgeServer* bridge, pb::SapiTts* tts)
{
    if (!tts) return;

    bridge->RegisterMethod("tts:voices", [tts](const json&) -> json {
        std::vector<pb::TtsVoiceInfo> voices;
        if (!tts->EnumerateVoices(voices)) return json::array();
        json out = json::array();
        for (const auto& v : voices) {
            out.push_back({
                {"id", v.id}, {"name", v.name}, {"language", v.language},
                {"gender", v.gender}, {"oneCore", v.oneCore},
            });
        }
        return out;
    });

    bridge->RegisterMethod("tts:speak", [tts](const json& p) -> json {
        pb::TtsSpeakOptions options;
        options.tag = p.value("tag", "");
        options.flush = p.value("flush", true);
        return json(tts->Speak(p.value("text", ""), options));
    });

    bridge->RegisterMethod("tts:pause", [tts](const json&) -> json { return json(tts->Pause()); });
    bridge->RegisterMethod("tts:resume", [tts](const json&) -> json { return json(tts->Resume()); });
    bridge->RegisterMethod("tts:stop", [tts](const json&) -> json { return json(tts->Stop()); });

    bridge->RegisterMethod("tts:status", [tts](const json&) -> json {
        return json{
            {"speaking", tts->IsSpeaking()},
            {"paused", tts->IsPaused()},
            {"voiceId", tts->CurrentVoiceId()},
            {"voiceName", tts->CurrentVoiceName()},
            {"rate", tts->Rate()},
            {"volume", tts->Volume()},
        };
    });

    bridge->RegisterMethod("tts:setVoice", [tts](const json& p) -> json {
        return json(tts->SelectVoice(p.value("voiceId", "")));
    });
    bridge->RegisterMethod("tts:setRate", [tts](const json& p) -> json {
        return json(tts->SetRate(p.value("rate", 0)));
    });
    bridge->RegisterMethod("tts:setVolume", [tts](const json& p) -> json {
        return json(tts->SetVolume(p.value("volume", 100)));
    });
}
