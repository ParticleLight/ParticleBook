# ParticleBook — 工作区契约
> 稳定注入。只放当前有效的硬约束和指针；会过期、会替换，不累积历史。

## 定位
- 内置 Z-Library 的电子书阅读器；**C++ Win32 + WebView2 原生应用**，React 前端由 WebView2 加载。
- Electron 版已废弃并移出仓库（清单见 `docs/memory/facts.md`）。
- 当前阶段：v2.2.0 已发布；`main` 领先 tag 的未发版提交：`6e7cb7d`（UI）、`3bbe555`（CI）。

## 红线
- **不要跑 `npm start` / `npm run dev`**，也不要恢复 Electron 残留（`src/main/`、`electron-builder.json`、`start.bat`、`electron-vite`、`release/`、`ensure-electron-stub.js`）。
- `bridge/contract.mjs` 是桥接契约唯一来源；`src/renderer/types/electron.d.ts` 是生成物，**禁止手改**（CI 比对新鲜度）。
- `vite.config.ts` 的 `base: './'` 与 `outDir: out/renderer` 是承重配置，别删。
- 动过 `particlebook-cpp/**/*.h` 后**不要增量编译**：先删 `build2/CMakeFiles` 下的 `*.obj` 再 nmake（ABI 不一致会启动即崩）。
- 外部动作（push / 发布 / PR）先问；数字必须来自磁盘或实跑；临时脚本用完即删；原子提交。
- `docs/memory/` 是本地笔记、**不入库**，不要提交。

## 每轮约定
- 前端：`npm run build` → `out/renderer/`；启动：`npm run start:cpp`；一键重建：`npm run rebuild:cpp`。
- C++ 构建（VS 在 D 盘）：`cd particlebook-cpp/build2 && MSYS_NO_PATHCONV=1 cmd /c "call D:/VS2022/VC/Auxiliary/Build/vcvars64.bat >nul 2>&1 && nmake"`。
- 校验：`npx tsc --noEmit` / `npm run build` / `npm run i18n:check` / `node scripts/check-bridge-contract.js` / `node scripts/gen-bridge-dts.mjs --check` / `node scripts/check-version-sync.js` / `ctest`。
- 版本号三处：`node scripts/bump-version.js <x.y.z>`。
- 分支 `main`；`out/`、`particlebook-cpp/build2/` 是 gitignore 的构建输出。

## 记忆地图
- **开工前读** `docs/memory/index.md`（索引 + 当前活跃；本机文件，不存在说明不是本机工作区）。
- 决策 / 命令 / 坐标 → `docs/memory/facts.md`；偏好 / 红线 → `docs/memory/prefs.md`；踩坑 → `docs/memory/pitfalls.md`；已完结进展 → `docs/memory/archive/YYYY-MM.md`。
- 设计稿：`docs/BRIDGE_CONTRACT_DESIGN.md`、`docs/IMPROVEMENT_PLAN.md`、`docs/IMPROVEMENT_PLAN_R2.md`。
- **待办清单 → `docs/BACKLOG.md`**（用户口述增补；PB-xxx 编号）。

## 当前活跃任务
- 待用户真机验证：① Z-Library 下载→导入书架全链；② 手动点 Z-Library 的进站体感；③ `9359681`「文件丢失」提示观感。
- 下一版本 **2.2.1 开发中**：`6e7cb7d` + `3bbe555` + PB-001..PB-025（全部 2.2.1，按 P0→P1→P2）；**不重做 2.2.0**；详见 `docs/BACKLOG.md`。
- 2.2.1 issue：#6 亮色设置页已修；#7 安卓适配待定方向（非本版范围）。
- 2026-10-26 进度：PB-001/PB-004/PB-005/PB-010/PB-011/PB-012/PB-014/PB-015/PB-016/PB-019/PB-020/PB-023 ✅；PB-002 翻译全链；PB-006 搜索全链（剩精确跳转）；PB-009 双栏/竖排（分屏未做）；PB-018 E2E 15/15；PB-021 标注 UI（剩 ReaderAdapter/PDF rect）。
- 存疑未改：书柜重命名 Esc 时序；readerStore 命令态跨书留存。
