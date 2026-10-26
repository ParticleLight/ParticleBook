# scripts/bench — 书库性能基准（PB-017）

真实 app + WebView2 + CDP 的 5000 本书库基准，用来验证网格/列表虚拟化、封面
LRU 与搜索过滤不会随书数线性劣化。做法与 `scripts/e2e` 相同：临时 profile
沙箱 + 临时 DB，不碰真实书库、不联网。

## 前置

- Windows 10/11 + WebView2 Runtime，Node.js 22+（全局 `fetch` / `WebSocket`）。
- 已构建 app 与 renderer（`npm run build` + C++ 构建，或 `npm run rebuild:cpp`），
  `ParticleBook.exe` 旁边有最新的 `renderer/index.html`。
- 当前没有正在运行的 `ParticleBook.exe`（会话级单实例互斥体；脚本会检测并报错）。

## 运行

```sh
# 默认 5000 本、1 轮、grid+list，结果写 scripts/bench/results/
npm run bench:library
# 或直接：
node scripts/bench/library-bench.mjs --app particlebook-cpp/build2/ParticleBook.exe
```

| 参数 | 说明 |
|---|---|
| `--app <path>` | 可执行文件（默认 `particlebook-cpp/build2/ParticleBook.exe`） |
| `--books <n>` | 假书数量（默认 5000） |
| `--runs <n>` | 重复轮数，每轮全新沙箱（默认 1） |
| `--out <dir>` | JSON/Markdown/截图输出目录（默认 `scripts/bench/results`） |
| `--mode <m>` | `grid` / `list` / `both`（默认 both） |
| `--timeout <ms>` | 启动等待上限（默认 120000） |
| `--keep` | 保留临时沙箱 |
| `--verbose` | 打印每轮明细 |

退出码：0 = 指标全部达标；1 = 有指标越界 / 运行失败；2 = 前置条件或参数错误。

## 测什么

1. **首屏**：从启动到第一张书卡可交互（含 WebView2 冷启动）。
2. **挂载 DOM**：网格卡片数 / 列表行数、全页 DOM 节点数（虚拟化后应与 5000 无关）。
3. **滚动**：60 步滚到底再回顶，记录 rAF 帧间隔 p50/p95/max，以及滚动过程中
   最大挂载卡片/行数。
4. **搜索过滤**：输入唯一书名 → 等 DOM 收敛到 1 本，测耗时。
5. **封面 LRU**：小范围上下滚动 3 次验证同一批卡片重新挂载会命中缓存
   （`window.__pbCoverCacheStats()`），并检查缓存大小不超上限；整轮滚动的
   misses 应远小于书数（懒加载，不是预取全部）。
6. **交互**：虚拟化后的第一张卡右键菜单仍能打开（`data-testid="book-card"`）。

当前阈值（本机/CI 参考，写死在脚本 `validate()`）：网格/列表挂载 < 200、
全页 DOM < 6000、滚动 p95 < 120ms、搜索 < 5000ms、封面缓存复用 > 0 次且
misses < max(800, books*0.8)。

## 输出

- `library-bench-<timestamp>.json`：每轮每模式的原始指标 + 汇总 + 失败列表。
- `library-bench-<timestamp>.md`：Markdown 表格（可直接贴 PR / 汇报）。
- `<runId>-grid.png` / `<runId>-list.png`：5000 本时的界面截图（证据）。
- 样例：`results/library-bench-2026-10-26T15-27-48-002Z.{json,md}`：5000 本，
  grid 挂载 25 卡 / 628 DOM 节点，滚动 p95 ≈ 19ms，搜索 ≈ 3ms，首屏 ≈ 934ms。

临时沙箱默认删除（`--keep` 保留），失败时保留并打印路径。
