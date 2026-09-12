# ParticleBook 改进计划 · 第二轮（Review 驱动）

> 依据：上一轮全面 review 的 11 项发现 + 第一轮计划兑现度核查。
> 基线：分支 improve/foundation，10 个 commit，ctest 5/5、桥接契约 92 一致、nmake EXIT=0。
> 本计划只列【尚未修复】的项；review 中已当场修复的 4 项见文末附录，不重复列入。

## 0. 前置决策（需拍板，阻塞 P0）

| # | 决策点 | 选项 | 影响 |
|---|---|---|---|
| D1 | v2.2 在途工作如何处置 | A 提交 / B stash / C 保持不动 | 不处置则 CI 完全无法运行（见 1.1） |
| D2 | PB_UPDATE_BASE 的目标语义 | A 仅版本检查可配（现状）+ 补文档 / B 端到端可配 | B 需放开下载 URL 白名单，会削弱现有 SHA-512 + URL 白名单加固 |
| D3 | 是否把前端 tsc 纳入 CI | A 纳入（须先清既有类型错误）/ B 暂不纳入 | 纳入可防类型回归，但需先还债 |

**D1 的证据**：在途工作 = 12 个 modified（49+/45-）+ 4 个未跟踪文件；C++ 与前端均已构建通过（nmake EXIT=0、前端 19.5s EXIT=0），处于可直接提交状态。

## 1. 阶段与项一览

| 阶段 | 项 | 价值 | 依赖 |
|---|---|---|---|
| P0 | 1.1 解除 CI 阻断 | 让已写好的 CI 真正生效 | D1 |
| P1 | 1.2 修正语义与注释不一致 | 消除误导 | D2 |
| P2 | 2.1 DatabaseService 原子写/损坏备份单测 | 性价比最高：最安全关键的代码零测试 | — |
| P2 | 2.2 PatchMobiEncoding 抽纯函数 + 单测 | 兑现第一轮承诺 | — |
| P2 | 2.3 CI 增加版本一致性校验 | 防发布版本漂移 | 1.1 |
| P3 | 3.1 encoding.h 的 WIN32_LEAN_AND_MEAN 集中化 | 结构性卫生 | — |
| P3 | 3.2 ParseLatestYaml 多文件健壮性 | 消除隐式假设 | — |
| P4 | 4.1 前端 tsc 类型债务 | 类型安全 | D3 |
| P4 | 4.2 M7 真·类型级契约生成 | 长期架构 | — |

---

## 1.1 P0 · 解除 CI 阻断

**现状**：两个 workflow 的 `npm run build` 都会调用 `node scripts/ensure-electron-stub.js`，而该文件未被 git 跟踪。

**证据**：
- `git ls-files --error-unmatch scripts/ensure-electron-stub.js` → NOT-IN-GIT
- `package.json` 的 build 脚本为 `node scripts/ensure-electron-stub.js && electron-vite build`
- `.github/workflows/build.yml` 与 `release.yml` 均执行 `npm run build`
- 脚本自身注释写明它是为了让 fresh clone (npm ci) 与 CI 能跑 build —— 设计给 CI 用却未入库

**改动**：提交 `scripts/ensure-electron-stub.js`（随 v2.2 一并，见 D1）。不推荐把逻辑内联进 build 脚本（多入口复用同一 stub）。

**验证（本项必须用干净克隆，不得用本机工作树）**：
```
git clone <repo> /tmp/pb-clean && cd /tmp/pb-clean
git checkout improve/foundation
npm ci && npm run build && npm run i18n:check
node scripts/check-bridge-contract.js
```
四项全部通过才算完成。

---

## 1.2 P1 · 修正语义与注释不一致

**现状**：`PB_UPDATE_BASE` 只改变了版本检查的主机；下载 URL 仍硬编码 github，并受白名单约束。

**证据**：
- `FileHandlers.cpp:257` 注释写 `PB_UPDATE_BASE = releases host (default github.com) + path prefix`，但实现只把它当 host 用（path 仍硬编码 `/ParticleLight/ParticleBook/releases/latest/download/latest.yml`）
- `update_meta.h:70-72` 的 `BuildDownloadUrl` 硬编码 `https://github.com/ParticleLight/...`
- `FileHandlers.cpp:732-733` 的 `kOfficialPrefix` 白名单会拒绝非 github 的下载 URL

**改动（按 D2 二选一）**：
- **A（推荐）**：修正 `FileHandlers.cpp:257` 注释为“仅版本检查主机可配”，并在 `update_meta.h` 的 `BuildDownloadUrl` 上方注明“下载源仍固定 github，受白名单保护”；README/文档同步一句说明。
- **B**：把下载白名单改为“可配置前缀白名单”（新增 `PB_UPDATE_DOWNLOAD_PREFIX`），并把 SHA-512 校验设为强制。**风险显著上升，需单独评估**。

**验证**：A 为纯注释/文档改动，构建通过即可；B 需补白名单绕过/命中两类单测。

---

## 2.1 P2 · DatabaseService 原子写与损坏备份单测（最高性价比）

**现状**：`WriteAtomic`（DatabaseService.cpp:91）、`BackupCorruptFile`（:119）、后台 WriterThread 去时写入与失败重试，是全应用最关键的**数据丢失防护**逻辑，**零测试**。

**可测性已核实**：`DatabaseService.h` 仅依赖 `<string> <mutex> <thread> <condition_variable> <chrono> <vector>` + `nlohmann/json.hpp`，**无 Win32/GUI 依赖**，可直接在 console 测试目标中链接。

**改动**：新建 `particlebook-cpp/tests/unit/database_test.cpp`，链接 `DatabaseService.cpp` + `third_party`（nlohmann 已入库）。用例：
1. 文件不存在时 `Load` → 默认结构齐备（books/nextId/settings 等），不崩
2. 损坏 JSON（截断）→ 生成 `*.corrupt-<ts>` 备份，且内存重置为空对象
3. 合法但非 object（如数组）→ 同样备份 + 重置（防 string-key operator[] 崩溃）
4. 写→`FlushSync` → 文件可被重新解析，且 `nextId` 持久化
5. `NextId` 单调递增
6. 写盘后目录内**无残留 `.tmp.` 文件**
7. 写入 5000 条后再 Load → 数据完整（防截断回归）

**风险**：中。DatabaseService 会启动后台写线程，测试须依赖析构 join 完成最终 flush；须用独立临时目录隔离，避免污染真实 `%APPDATA%`。

**验证**：`ctest` 新用例全绿；并做变异测试——令 `BackupCorruptFile` 不写备份，用例 2/3 必须变红。

---

## 2.2 P2 · PatchMobiEncoding 抽纯函数 + 单测（第一轮未兑现）

**现状**：第一轮计划 §6 测试矩阵承诺了 `mobi_patch_test.cpp`，但核查 `tests/unit/` 只有 5 个文件，**该测试从未创建**；`PatchMobiEncoding` 至今仍是 `PdfService.cpp:56` 的文件内 `static`，不可测。

**改动**：
- 抽为 `src/utils/mobi_patch.h`：内存版 `bool PatchMobiEncodingBuffer(std::vector<uint8_t>& data)`（纯函数，无 IO）+ 保留文件 IO 包装在 PdfService
- 新建 `tests/unit/mobi_patch_test.cpp`：MOBI magic + encoding=0 → 65001；encoding=1252 → 65001；已是 65001 时**不变**；无 magic 时不变；`.mobi/.azw/.azw3` 与其它扩展名的分流（分流逻辑留在 PdfService 测其判定函数）

**风险**：中低。触碰 PdfService 运行路径，须保持行为逐位一致。

**验证**：ctest 新用例全绿；真机导入中文 MOBI 确认渲染与修复前一致。

---

## 2.3 P2 · CI 增加版本一致性校验

**现状**：版本号在三处各存一份（`package.json`、`CMakeLists.txt` 的 `project(... VERSION ...)`、`installer.nsi`），已有 `scripts/bump-version.js` 负责同步，但**无任何校验**；三处当前均为 2.1.0。

**改动**：新增 `scripts/check-version-sync.js` 比对三处一致，不一致则 exit 1；挂入 `build.yml`（与 i18n、契约校验并列）。

**验证**：本地跑 exit 0；手工把 `installer.nsi` 改成 2.1.1 → 脚本必须 exit 1。

---

## 3.1 P3 · encoding.h 的 WIN32_LEAN_AND_MEAN 集中化

**现状**：`encoding.h:17-18` 以工具头身份 `#define WIN32_LEAN_AND_MEAN`（为修 msxml.h 与 tinyxml2 的 XMLDocument 冲突而引入）。副作用波及所有包含者，属重手做法。

**改动**：新建 `src/utils/pb_win.h`，集中 `#define WIN32_LEAN_AND_MEAN` + `#include <windows.h>`；`encoding.h` 改为包含它。目标是让“Windows 头引入”只有一个入口。

**风险**：低。**验证**：全量构建 EXIT=0 + 5 个单测全绿。

---

## 3.2 P3 · ParseLatestYaml 多文件健壮性

**现状**：`update_meta.h` 的 `field()` 取**首个** `url:` / `size:` / `sha512:`，在真实 electron-builder latest.yml（同时含 setup.exe 与 setup.exe.blockmap）下依赖“首块即安装包”的隐式假设。

**改动**：解析 `files:` 列表，**显式挑选 `.exe` 且非 `.blockmap`** 的条目；无匹配时回退首个（保持与旧行为兼容）。

**验证**：新增用例覆盖两种排列（blockmap 在前 / 在后）与“只有 blockmap”的退化情形。

---

## 4.1 P4 · 前端 tsc 类型债务

**现状**：`npx tsc --noEmit` 实测 **25 处错误，分布 7 个文件**（均为既有问题，非本轮引入）：

| 文件 | 错误数 | 主要类型 |
|---|---|---|
| components/Reader/EpubRenderer.tsx | 11 | epubjs `Spine.items/spineItems`、`Contents` 索引 |
| stores/bookSourceStore.ts | 4 | 类型推断 |
| components/Reader/PdfRenderer.tsx | 3 | `info` 可能为 null |
| App.tsx | 3 | setTimeout 返回类型 |
| utils/fileReader.ts | 2 | **v2.2 新增文件（尚未提交）自带** |
| components/Reader/TextRenderer.tsx | 1 | 类型推断 |
| components/Library/Library.tsx | 1 | string \| undefined |

当前 CI 无 tsc 步骤。注：`fileReader.ts` 的 2 处说明 v2.2 在途工作本身也带类型债，提交前宜一并处理。

**改动**：按类修复（本地 interface 扩展或精确断言），清理后把 `tsc --noEmit` 挂入 CI（依赖 D3）。

**风险**：低（纯类型层，不改运行时）。**验证**：tsc 退出 0，且前端构建仍 EXIT=0。

---

## 4.2 P4 · M7 真·类型级契约生成（长期）

**现状**：M7 落地为**方法名级**校验（92 个方法名与 d.ts 成员一致），未做类型级生成；`electron.d.ts` 仍为手写。

**改动**：C++ 侧引入方法元数据表（名 + 参数/返回类型描述），构建期生成 `electron.d.ts`；契约校验升级为比对生成物与仓库文件。

**风险**：高（触及桥接核心与构建链）。建议在 1.x–3.x 全部落地、CI 稳定后再启动。

---

## 5. 关键路径与顺序

```
D1 → 1.1 → 2.3               （CI 先真正跑起来，成为后续护栏）
2.1 / 2.2                     （可并行，独立于 CI）
1.2                           （依赖 D2）
3.1 / 3.2                     （独立，低风险）
4.1                           （依赖 D3）→ 4.2（最后）
```

## 6. 本轮新增的验证纪律（来自 review 教训）

1. **干净克隆验证**：凡涉及 CI / 构建脚本的改动，必须在 `git clone` 出的临时目录里实跑，**不接受本机工作树验证**。
2. **变异测试证明**：每个新增测试都要附一次“故意破坏实现 → 测试变红 → 还原”的记录，证明测试有牙齿。
3. **数字取自磁盘**：行数/计数一律用 `git show --numstat` 等实测值，不引用记忆值。
4. **提交原子性**：一个提交只含一个主题，且提交信息必须覆盖其全部内容（M1 混入 v2.2 改动是反面案例）。

## 7. 完成定义（DoD）

- 每项：C++ 构建 EXIT=0、相关 ctest 全绿、契约校验通过。
- CI 类改动：干净克隆等价验证通过，并在 GitHub 上实际跑绿一次。
- 分支结束状态：**工作树干净**（无未提交 modified、无未跟踪的必需文件）。

## 附录 · 本轮 review 已当场修复（不重复列入计划）

- `base64_test.cpp` 的 `|| true` 假断言 → 改为正确断言（实测 3 字节 41 42 43），并补 `+` `/` 字母表与 MIME 换行用例
- `PdfService::ExtractText` 第 4 处 mutool 调用点补上 `QuoteCmdArg`（M5 遗漏）
- `test_assert.h` 的 `CHECK_EQ` 重写为可打印左右值；移除 MSVC x64 上与 `unsigned long long` 冲突的 `size_t` 重载
- 清理 11 个 `scripts/*.mjs` 探针脚本与 `.scratch/`
- `docs/IMPROVEMENT_PLAN.md` 入库并标注 M0–M7 落地状态与遗留项
- 提交：`e73380f`（review 修复）、`8faf576`（计划文档）
