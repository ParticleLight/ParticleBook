#pragma once

class BridgeServer;
class DatabaseService;
namespace pb { class JobService; }

// PB-004: export bookmarks/highlights/notes as Markdown/CSV/JSON.
// bookId == 0 exports the whole library; bookId > 0 exports one book.
void RegisterExportHandlers(BridgeServer* bridge, DatabaseService* db, pb::JobService* jobs);
