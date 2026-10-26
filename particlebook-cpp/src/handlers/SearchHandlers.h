#pragma once
class BridgeServer;
class DatabaseService;
namespace pb { class JobService; class SearchIndexService; }
void RegisterSearchHandlers(BridgeServer* bridge, DatabaseService* db,
                            pb::JobService* jobs, pb::SearchIndexService* index);
