#pragma once
class BridgeServer;
class DatabaseService;
class SecretStore;
namespace pb { class JobService; class TranslationService; }
void RegisterTranslationHandlers(BridgeServer* bridge, DatabaseService* db,
                                 pb::JobService* jobs, SecretStore* secrets,
                                 pb::TranslationService* translation);
