#pragma once
class BridgeServer;
namespace pb { class SapiTts; }
void RegisterTtsHandlers(BridgeServer* bridge, pb::SapiTts* tts);
