#pragma once

class BridgeServer;
class DatabaseService;
namespace pb { class JobService; }

// PB-011: backup:create / backup:restore handlers. Both run through JobService
// because a 5 MB library takes ~1s; restore suspends the DB writer first.
void RegisterBackupHandlers(BridgeServer* bridge, DatabaseService* db, pb::JobService* jobs);
