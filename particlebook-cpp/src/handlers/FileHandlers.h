#pragma once

class BridgeServer;
class DatabaseService;
class ContentCache;
namespace pb { class JobService; }

void RegisterFileHandlers(BridgeServer* bridge, DatabaseService* db, ContentCache* cache, pb::JobService* jobs);
