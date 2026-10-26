#pragma once

class BridgeServer;
class DatabaseService;

// PB-005：书籍元数据（tags / series_name / series_index / rating + 基础字段）桥接入口。
//
// methods（与 bridge/contract.mjs 的 member 对应）：
//   book:updateMetadata { id, patch } -> PbBook | null
//   library:getTags     {}            -> string[]
//   library:getSeries   {}            -> string[]
//
// 校验全部在 DatabaseService::UpdateBookMetadata 内做（白名单字段、空书名、
// 类型不对都返回 null 且不写库）；handler 只做参数形状检查。Z-Library 外部
// 页面不走白名单（BridgeServer 只允许 app origin 调这些方法）。
void RegisterBookMetadataHandlers(BridgeServer* bridge, DatabaseService* db);
