#pragma once

class BridgeServer;
class DatabaseService;

// PB-021：统一标注（annotations）的桥接入口。
//
// methods（与 bridge/contract.mjs 的 member 对应）：
//   annotations:list   { bookId?: number }            -> PbAnnotation[]
//   annotations:add    { annotation: PbAnnotationInput } -> PbAnnotation | null
//   annotations:update { id: number, patch: object }  -> PbAnnotation | null
//   annotations:delete { id: number }                 -> {}
//
// 入参在这里先用 domain/Annotation.h 规范化 / 校验（type、locator、颜色、
// 时间戳），再交给 DatabaseService 做 id 分配与旧表双写。非法输入返回 null，
// 不抛异常、不写库；渲染层按 null 显示"标注无效"即可。
void RegisterAnnotationHandlers(BridgeServer* bridge, DatabaseService* db);
