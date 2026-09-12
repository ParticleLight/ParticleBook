# ParticleBook 改进计划

> **状态：M0–M7 已全部落地（分支 `improve/foundation`）。**
> 落地内容：公共 utils 抽取（encoding/base64/fnv1a/update_meta/win_cmd）、FNV-1a 稳定哈希、
> CTest 单测基础设施（5 个用例）、更新域名可配置、mutool 命令行 QuoteCmdArg 规范化、
> CI 追加 ctest 与桥接契约校验。执行中另修复 3 处真实缺陷（encoding.h 的 Windows 头冲突、
> VersionGreater 无法解析 v 前缀、electron.d.ts 缺 5 个 PDF 桥接方法）。
>
> 遗留（见 review 结论）：M7 为方法名级契约校验，未做 electron.d.ts 全量类型生成；
> `PB_UPDATE_BASE` 仅覆盖版本检查主机，下载 URL 仍受 github 白名单约束。
> 依据 2025-08 代码评审（详见对话评析）。当前工作区含**进行中的 v2.2 未提交改动**（虚拟主机文件服务、Zlib cert 作用域、双语收尾），本计划所有改动应**叠加在该分支之上**，避免与未提交工作冲突。

## 0. 前置：分支与基线（必须在任何改动前完成）
