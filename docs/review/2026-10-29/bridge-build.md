# 桥接、构建与 CI review（2026-10-29）

> 审查对象：`F:\VScode\Read` @ `1feb175`（工作区干净）。范围：`bridge/contract.mjs`、`scripts/`（check-* / gen-bridge-dts / bump-version / e2e）、`.github/workflows/*.yml`、`particlebook-cpp/CMakeLists.txt`、`particlebook-cpp/tests/**`、`src/renderer/types/electron.d.ts`（只读）。
> 方法：只读静态审查 + 独立脚本复核（不复用仓库脚本的判定逻辑，避免"用同一套逻辑证明它自己"）。**未运行** 构建 / ctest / E2E / 应用。

## 概要（最该先修的三件事）

1. **发布安装包漏了 `mobitool.exe`（P1）**：`installer.nsi` 的 `File` 清单里没有它 → 安装版/更新版的 AZW3/KF8 转换直接失效，回退成 mutool 文本探针（格式、目录、封面全丢）。CMake `install()` 里有这条，但发布链路走的是 NSIS，根本不执行 `cmake --install` —— 两条打包路径已经分叉。
2. **`installer.nsi` 的 renderer 清单是硬编码白名单**：只拷 `index.html` / `pdf.worker.min.mjs` / `assets/*.js|css`，漏了 `renderer/app-icon.png`（「关于」页图标在安装版必破图）；且以后 Vite 产出任何 png/woff/svg，都会静默漏包，本机构建永远发现不了。
3. **CI 校验矩阵有缺口**：`check:changelog` 这条护栏从未在任何 workflow 里执行；`release.yml`（tag 发布）也不跑 ctest / bridge-handlers / version-sync —— tag 指向一个没走过 build 的 commit 时没有任何兜底。

## 发现

| ID | 严重度 | 位置(file:line) | 问题 | 影响 | 建议 |
|---|---|---|---|---|---|
| F1 | **P1** | `particlebook-cpp/scripts/installer.nsi:135-137`；对照 `particlebook-cpp/CMakeLists.txt:183-185` | 安装包未打包 `mobitool.exe` | 安装版 AZW3/KF8 → EPUB 转换失效（`FileFormatService` 只在 exe 同目录找），导入退化成 mutool 文本探针 | NSIS 里补 `File /nonfatal "..\build2\mobitool.exe"`；发布前用实体安装包验一次 AZW3 导入 |
| F2 | P2 | `installer.nsi:144-151`；`src/renderer/components/Settings/GlobalSettingsPages.tsx:805` | renderer 静态资源白名单漏 `app-icon.png`（且只列 js/css 两类） | 安装/更新后「关于」页图标 404；未来任何非 js/css 产物静默漏包 | 改成"整目录拷贝 + 排除 map"（`File /r "..\build2\renderer\*.*"`），或加脚本比对 `out/renderer` 与 nsi 清单 |
| F3 | P2 | `.github/workflows/build.yml:27-49`、`release.yml:33-46`；对照 `package.json:14` | `check:changelog` 从未在 CI 执行 | 「漏翻/版本倒序」护栏形同虚设，英文界面可静默回落到中文 | build.yml 增加 `npm run check:changelog`（release.yml 也加，或收敛为单一 `npm run check:all`） |
| F4 | P2 | `release.yml:33-46`；`build.yml:3-6` | tag 发布路径不跑 ctest / check:bridge-handlers / check-version-sync；build.yml 只对 main push 生效 | 从本地未推 main 的 commit 打 tag 即可发布零测试构建；handlers 漂移也可能漏到包里 | release.yml 至少补 `ctest` + `npm run check:bridge-handlers` + `node scripts/check-version-sync.js` |
| F5 | P2 | `CMakeLists.txt:167-178` | POST_BUILD `copy_directory` 拷 `resources` 与 `../out/renderer` 没有 `if(EXISTS)` 守卫 | 前端没 build 时 C++ 构建直接失败（与 2026-10-29 mobitool 全红同型）；错误信息是 CMake 的 "Error copying directory" | 与 mobitool 一致：配置期 `if(NOT EXISTS)` 给 WARNING + 构建期守卫，或明确文档化"必须先 build 前端" |
| F6 | P2 | `scripts/audit-bridge-usage.mjs:29-33` | 两处盲区：① 排除生成物用 `f.endsWith("types\\electron.d.ts")`（Windows 分隔符）② 只扫 `src/renderer` + `particlebook-cpp/src`，不扫 `particlebook-cpp/resources/*.js` | 非 Windows 上排除失效 → 审计假绿（正是该脚本注释要防的坑）；注入脚本里的消费者漏判 → 误报死接口 | 用 `path.basename(f) === 'electron.d.ts'` 或 `f.split(path.sep).join('/')`；`walk` 增加 `particlebook-cpp/resources` |
| F7 | P2 | `bridge/contract.mjs:336-339`、`particlebook-cpp/src/BridgeServer.cpp:322`、`particlebook-cpp/src/handlers/FileHandlers.cpp:199` | 校验只比"成员名/元数/方法名"，**不比 JSON 参数键名**；表里 `filePath`、stub 里 `path`、C++ 读 `path` 已经三处不一致 | 改 stub 参数键不会有任何检查拦截，只在运行时表现为"参数丢失/默认值"（E2E 只覆盖少数方法） | 给 contract 的 `params[].n` 定成"运行时键名"并加校验：从 stub 提取 `invoke('x', {k1,k2})` 的键，与表比对；或加一条 stub↔C++ 的键名冒烟用例 |
| F8 | P2 | `scripts/bump-version.js:15-20`、`scripts/check-version-sync.js:28-32` | 版本只同步三处，`package-lock.json` 没同步（现为 2.1.0，`package.json` 是 2.2.0） | 每次发版后锁文件版本漂移；`npm install` 重写产生噪声 diff，仓库元数据自相矛盾 | `bump-version.js` 顺带改 lock 的 `version`/`packages[""].version`；`check-version-sync` 加第四处 |
| F9 | P2 | `scripts/e2e/smoke.mjs:1285`、`scripts/e2e/lib/mock-zlib-server.mjs:44-52` | E2E 的 zlib 真实下载 mock 固定监听 **80** 端口（因 C++ 手写 URL 解析忽略 `:port`），且 E2E 只挂在 schedule/manual | CI/开发机上 80 被占（IIS、代理、其它服务）→ 整条 E2E 红；push/PR 无 E2E 保护 | 端口占用时明确报 "port 80 busy" 并跳过该步（标记 SKIP 而非 FAIL）或给 C++ 修 :port 解析；把 e2e 至少挂到每周 + 发版前 workflow_dispatch |
| F10 | P2 | `CMakeLists.txt:196-362`；`installer.nsi:135-151` | 测试覆盖缺口：BridgeServer 的 `HandleMessage`/`ProcessInvoke`（origin 门禁、Method not found、async reject）无单测；**安装包文件清单无任何自动化校验** | F1/F2 这两处漏包正是"没有守卫"的直接结果，下一次同类改动还会漏 | 加 `bridge_server_test`（纯 JSON 分发可测）；加 `scripts/check-installer-files.js`：解析 nsi 的 `File` 行 vs `build2` 实际产物/必需清单 |

## 详细分析

### F1 安装包漏 `mobitool.exe` → 发布版 AZW3 转换失效（P1）

`installer.nsi` 的"Core application"只有三个文件，全文没有任何 mobitool 字样（`grep -n "mobitool" installer.nsi` 为空）：

```nsis
; particlebook-cpp/scripts/installer.nsi:134-137
    ; Core application
    File "..\build2\ParticleBook.exe"
    File "..\build2\mutool.exe"
    File "..\build2\WebView2Loader.dll"
```

而 CMake 的 install 规则里是有的（并且带 EXISTS 守卫）——说明"随包分发"的意图早就写下了：

```cmake
# particlebook-cpp/CMakeLists.txt:183-185
if(EXISTS "${CMAKE_SOURCE_DIR}/third_party/mobitool/mobitool.exe")
    install(FILES "${CMAKE_SOURCE_DIR}/third_party/mobitool/mobitool.exe" DESTINATION .)
endif()
```

运行期查找是"exe 同目录"，找不到就明确报错并回退：

```cpp
// particlebook-cpp/src/handlers/FileFormatService.cpp:163-168
const std::filesystem::path toolPath =
    std::filesystem::path(exePathBuf).parent_path() / L"mobitool.exe";
std::error_code ec;
if (!std::filesystem::exists(toolPath, ec)) {
    result.error = "mobitool.exe not found next to app";
    return result;
}
```

`docs/AZW3.md:15-16` 的设计结论是"像 mutool 一样把 mobitool.exe 作为独立工具随程序分发"；`FileImportService.cpp:207-208` 注明 mobitool 失败时"回退现有 mutool 文本探针"。**E2E 之所以发现不了**：E2E 跑的是 `build2` 目录（CMake POST_BUILD 已把 mobitool 拷进去），不是安装后的目录。发布前必须用实体安装包验一次 AZW3 导入（AGENTS.md 里"installer 包含 mobitool/resources"这条待办正是它）。

### F2 renderer 静态资源白名单漏 `app-icon.png`（P2）

```nsis
; particlebook-cpp/scripts/installer.nsi:144-151
    ; Renderer
    SetOutPath "$INSTDIR\renderer"
    File "..\build2\renderer\index.html"
    File /nonfatal "..\build2\renderer\pdf.worker.min.mjs"

    SetOutPath "$INSTDIR\renderer\assets"
    File "..\build2\renderer\assets\*.js"
    File "..\build2\renderer\assets\*.css"
```

「关于」页引用的是 `./app-icon.png`（`GlobalSettingsPages.tsx:805`），构建产物根目录确实有 `out/renderer/app-icon.png`（`src/renderer/public/app-icon.png` 由 Vite 复制），但 nsi 没拷 → 安装后 `$INSTDIR\renderer\app-icon.png` 不存在，`<img>` 破图。更长期的问题：nsi 把"渲染层产物"限定成 js/css 两类，未来任何 png/woff/svg（字体、占位图、地图）都会被静默漏掉，而本地开发（直接跑 build2）永远看不出来。建议改成整目录拷贝（`File /r`）+ 显式排除 `*.map`；如需保持小体积，加一个 `scripts/check-installer-files.js` 把 `out/renderer` 的文件类型与 nsi 规则做比对。

### F3 `check:changelog` 从未在 CI 执行（P2）

`package.json:14` 定义了 `check:changelog`，脚本本身会在缺译文时 `exit 1`：

```js
// scripts/check-changelog.mjs:84-88
if (problems.length) {
  console.log("=== PROBLEMS (" + problems.length + ") ===")
  for (const p of problems) console.log("  " + p)
  process.exit(1)
}
```

但两个 workflow 的所有 `run:` 步骤里都没有它：

```yaml
# .github/workflows/build.yml:27-49（校验段完整摘录）
      - run: npx tsc --noEmit
      - run: npm run build
      - run: npm run i18n:check
      - run: node scripts/check-bridge-contract.js
      - run: npm run check:bridge-handlers
      - run: node scripts/gen-bridge-dts.mjs --check
      - run: node scripts/audit-bridge-usage.mjs
      - run: node scripts/check-version-sync.js
```

AGENTS.md 把它列为校验全家桶的一员、prefs 里也写了"和代码同一个提交"，但实际只有本地手动跑的人才会被拦。`check-i18n-keys.js` 扫不到 changelog 的 key（`t(change.text)` 是变量），所以这条护栏是唯一兜底 —— 不接进 CI 等于没有。

### F4 tag 发布路径的校验少于 main 构建（P2）

`build.yml` 只在 `push: branches: [main]` / PR / dispatch / schedule 触发（`build.yml:3-10`）；`release.yml` 只在 tag 与 dispatch 触发（`release.yml:3-8`）。tag push 不匹配 `branches: [main]`，所以 **tag 不会触发 build.yml**；而 `release.yml` 的校验段只有 tsc / build / i18n / contract / dts：

```yaml
# .github/workflows/release.yml:33-46
      - run: npx tsc --noEmit
      - run: npm run build
      - run: npm run i18n:check
      - run: node scripts/check-bridge-contract.js
      - run: node scripts/gen-bridge-dts.mjs --check
```

于是：`ctest`（18 个单测）、`check:bridge-handlers`（契约 invoke ↔ RegisterMethod）、`check-version-sync`（三处版本，含 installer.nsi）都不在发布路径上（release.yml 只在 `Resolve version` 里内联比了 package.json vs CMakeLists 两处）。正常流程下 tag 的 commit 已推 main 并有绿构建，但这是一个**纯靠纪律、没有机制**的保证。建议 release.yml 复用 build.yml 的校验段（或抽一个 composite action）。

### F5 POST_BUILD `copy_directory` 没有 EXISTS 守卫（P2）

```cmake
# particlebook-cpp/CMakeLists.txt:167-178
add_custom_command(TARGET ParticleBook POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_directory
        "${CMAKE_SOURCE_DIR}/resources" "$<TARGET_FILE_DIR:ParticleBook>/resources")
add_custom_command(TARGET ParticleBook POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_directory
        "${CMAKE_SOURCE_DIR}/../out/renderer" "$<TARGET_FILE_DIR:ParticleBook>/renderer")
```

`resources/` 是入库的（`git ls-files particlebook-cpp/resources` 有 2 个 js），但 `out/renderer` 是 gitignore 的构建产物。**全新 clone 只按 AGENTS 里的"C++ 构建"命令跑 nmake，会在链接成功后死在 POST_BUILD**，报 CMake 的 "Error copying directory"，看起来像构建系统坏了。这与 2026-10-29 那次 "mobitool.exe 不存在 → 每个提交 CI 全红" 是同一类问题（当时修了 mobitool 的守卫，但没顺手补这条）。建议：配置期 `if(NOT EXISTS out/renderer)` 给 FATAL 并提示 "先 npm run build"，或 POST_BUILD 前用 `if(EXISTS)` 守卫（缺 renderer 时产品本来也起不来，不如早报）。

### F6 桥接审计脚本的两处盲区（P2）

```js
// scripts/audit-bridge-usage.mjs:29-33
walk(join(ROOT, "src/renderer"))
walk(join(ROOT, "particlebook-cpp/src"))
const consumers = files.filter((f) =>
  !f.endsWith("types\\electron.d.ts") && !f.endsWith("BridgeServer.cpp")
);
```

① 第 32 行用的是 **Windows 分隔符** `types\electron.d.ts`；`path.join` 在 Linux/macOS 上产出 `/`，`endsWith` 永远 false → 生成的 d.ts（含全部成员名）会被当成消费者，审计必然"全绿"。这正是脚本头注释第 1 条想防的坑，只在 Windows 上成立。② 第 29-30 行只扫两棵源码树，**不扫 `particlebook-cpp/resources/*.js`** —— 而那正是 2026-10-29 起 Z-Library 注入脚本的"可编辑真源"（`App.cpp` 里只剩内置回退）。当前 6 个 `zlib:download*/import*` 事件恰好也在 `App.cpp` 的回退脚本里出现，所以没暴露；一旦未来某个消费者只写在 resources 里，就会误报"死接口"并诱导删除（pitfalls 里记过 6 个事件被误删的事故）。

### F7 参数键名（JSON 形状）没有任何护栏（P2）

```js
// bridge/contract.mjs:335-339
  {
    member: "readFile", method: "file:read",
    kind: "invoke",
    group: "Files",
    params: [{ n: "filePath", t: "string" }],
```

```js
// particlebook-cpp/src/BridgeServer.cpp:322（stub 真正发出去的键是 path）
    readFile: async function(path) { var r = await invoke('file:read', {path:path}); ...
```

```cpp
// particlebook-cpp/src/handlers/FileHandlers.cpp:199（C++ 读的也是 path）
            const std::string path = p.value("path", "");
```

`check-bridge-contract.js` 只比较成员名、参数**个数**、方法名（`:82-96`），不比较键名；而生成的 d.ts 用的是表里的 `filePath` —— **同一份契约里已经出现三处不同的参数叫法，检查全绿**。目前 JS 调用是位置参数、stub 固定键名，所以不炸；但只要有人改 stub 的键（比如把 `path` 改成 `filePath` 图个一致），C++ 会静默读到空串，表现为"文件读不出来"。E2E 只覆盖 readFile/importBooks 等少数方法，多数 db:* 没有端到端保护。建议把表里的 `n` 定义成"运行时键名"，并在 check 里从 stub 的 `invoke('x', {k1, k2})` 字面量中抽键比对（stub 全是单行，容易解析），再补一条"C++ handler 读到的键 ⊆ stub 发送的键"的冒烟测试。

### F8 `package-lock.json` 版本漂移（P2）

```json
// package.json:3
  "version": "2.2.0",
// package-lock.json:3
  "version": "2.1.0",
// package-lock.json:9
      "version": "2.1.0",
```

`bump-version.js:15-20` 只改 `package.json`，`check-version-sync.js:28-32` 只比 package.json / CMakeLists / installer.nsi 三处 —— 锁文件从来没进版本同步。当前 `npm ci` 不受影响（不校验 root version），但每次 `npm install` 都会重写这两行产生噪声 diff，也让"版本唯一性"这句话出现第四个不受控副本。建议 bump 脚本顺带写 lock（或改用 `npm version --no-git-tag-version`），check 脚本补第四处。

### F9 E2E 依赖固定 80 端口，且不在 push/PR 上跑（P2）

```js
// scripts/e2e/smoke.mjs:1283-1285
  // 端口必须是 80：ZLibraryService 下载线程手写 URL 解析，WinHTTP 固定用协议默认端口
  // （http=80），会忽略 URL 里显式的 :port（产品既有实现限制，已记录在 docs/E2E.md）。
  const mock = await startMockZlibServer({ epubBuffer, fileName: 'pb-zlib-download.epub', port: 80 })
```

```yaml
# .github/workflows/build.yml:69-72
  e2e-smoke:
    if: github.event_name == 'workflow_dispatch' || github.event_name == 'schedule'
    runs-on: windows-latest
```

port 80 在开发机/CI 上不是"理所当然空闲"的（IIS、代理、其它本地服务都可能占）；一旦被占，`server.listen` 抛错 → 整条 E2E 红，且报错信息对不熟悉背景的人不可读。另外 E2E 要等每周一 03:17 UTC 或手动 dispatch 才跑，push/PR 完全没有关键路径（下载→导入、AZW3、翻译 wire format）的保护 —— 作者用 `--fail-at` 等机制做了自测，但回归发现仍是"下周才知"。建议：`listen` 失败时输出 "port 80 occupied by ...，跳过 zlib-real-download（SKIP）" 并把该步从 PASS 计数里剔除（报告里显式标 SKIP），同时把 e2e-smoke 挂到 tag 发布前的 dispatch 或 nightly。

### F10 测试与打包守卫的缺口（P2）

`CMakeLists.txt:196-362` 注册了 18 个测试目标（6 个 header-only + 12 个带实现的），覆盖 Database / JobService / SecretStore / Translation / SearchIndex 等；`test_assert.h:35-40` 的 `Summary()` 在失败时返回 1（不是空跑）。缺口在两块：

1. **BridgeServer 本身没有单测**：`HandleMessage`（origin 门禁：external 只允许 3 个方法、unknown 直接丢）、`ProcessInvoke`（Method not found / async reject）、`InvokeMethod`（5 分钟等待、异常吞成 null）全靠 E2E 的几个方法间接覆盖。这些是纯 JSON 逻辑，抽出来可测，且是安全边界。
2. **安装包文件清单没有守卫**：`installer.nsi` 的 8 条 `File` 是手写的，F1/F2 两处漏包正是"没有任何脚本比对 nsi 清单 vs 构建产物"的直接结果。建议加 `scripts/check-installer-files.js`：解析 nsi 的 `File` 行（含通配）+ 一个"必备文件清单"（ParticleBook.exe / mutool.exe / mobitool.exe / WebView2Loader.dll / resources/*.js / renderer/index.html / renderer/app-icon.png ...），与 `build2` 实际产物比对，接进 build.yml。

## 存疑 / 未覆盖

1. **E2E 在 github 托管 runner 上到底能不能跑**（未验证）：WebView2 Runtime 是否预装、Session 0 里能否创建可见窗口/渲染、80 端口是否空闲，都需要真实跑一次。验证方法：`gh workflow run build.yml`（或手动 dispatch）看 `e2e-smoke` job 日志；本地绿不能证明 CI 绿。相关资料：Windows runner 镜像的软件清单里通常带 WebView2 Evergreen Runtime，但本仓库从未在 CI 跑过 E2E（`build.yml:71` 的 schedule 分支没有历史可查）。
2. **`CMakeLists.txt:191` 的 `install(DIRECTORY "$<TARGET_FILE_DIR:ParticleBook>/renderer" ...)` 是否支持生成器表达式**（未验证）：本机没有走 `cmake --install` 的链路（发布用 makensis），所以这条规则可能是死代码、也可能一跑就报错。验证方法：在临时构建目录执行 `cmake --install particlebook-cpp/build2 --prefix <tmp>` 看是否展开。
3. **`InvokeMethod` 的 5 分钟同步等待**（`BridgeServer.cpp:205-208`）在 job 被取消 / 应用退出时的行为未实测：如果 `JobService` 在 shutdown 时丢弃回调，Z-Library/书源下载线程会阻塞到超时，可能拖慢退出。验证方法：下载中触发取消 + 退出，观察线程 join 时间 / 卡顿。
4. **`zlib:navigate` 仍在外部页面白名单**（`BridgeServer.cpp:62-66`），但 `scripts/bridge-handler-allowlist.json` 里该条 reason 写的是"曾列入外部页面白名单…当前无调用方"——文档与代码不一致。低危（handler 只做 back/forward/reload），但白名单 reason 是安全决策的依据，应让 C++ 侧确认是有意保留还是该删。
5. **`docs/memory/facts.md` 记的契约规模是"115 个成员（100 invoke / 14 event / 1 local）"，实际是 117（102/14/1）**（本次独立统计）。不影响代码，属记忆档案漂移，交接前值得同步。
6. **`check-bridge-contract.js:34-36` 的 stub 切片用"第一个 `};`"**：当前 stub 内没有 `};`（只有 `});`），一旦未来某行写成 `};` 结尾，切片会提前截断 → 大量"表有、存根无"假红（不会假绿，会阻塞合入）。属可维护性风险，未列为独立条目。

## 已核对且没问题的点

1. **契约 ↔ 存根 ↔ C++ 三方一致**（我用独立脚本复算，未复用仓库脚本判定）：contract 117 条 = stub 117 个成员，名称集合零差异、参数个数零差异、每条 `invoke/onEvent` 的方法名与表一致；102 个 invoke 方法全部有 `RegisterMethod/RegisterAsyncMethod`；14 个 event 全部有字面量 `EmitEvent`；另外 6 个 `zlib:download*/import*` 事件不在契约里，走注入脚本的原生消息通道（audit 有反向检查，属于设计而非遗漏）。
2. **生成物新鲜度校验对 CRLF 友好**：`gen-bridge-dts.mjs:19-25` 在比对前把两侧 `\r` 都去掉，写回时沿用文件既有行尾；`--check` 在 CI 里跑（`build.yml:42-43`），不会因为 `core.autocrlf` 产生假红。
3. **版本三处 + bump 脚本**：`check-version-sync.js` 覆盖 package.json / CMakeLists / installer.nsi，缺失时 `grab()` 返回 null 也会被 `uniq.has(null)` 判失败；release.yml 另有一次内联复核（defence in depth）。
4. **第三方二进制的打包约定成立**：`mupdf-windows.zip`（约 88 MB）与 `mobitool-windows.zip`（196 KB）都入库，zip 内路径分别正好是 `mupdf-1.25.0-windows/mutool.exe` 与 `mobitool/mobitool.exe`（与 CMake 解包目录一致）；mobitool 的解包失败只 WARNING，POST_BUILD 复制与 install 都有 `if(EXISTS)` 守卫（`CMakeLists.txt:153-161,183-185`），不会再出现 2026-10-29 那种全红。
5. **E2E 断言不是假通过**：18 个 `step()` 全部 `await` 且失败即 `throw`（`smoke.mjs:175-189`），最终 `state.ok` 决定 exit code（`:1655`）；AZW3 步要求真实转换产物 >100 KB + 封面 + iframe 正文（`:1451-1478`），translation mock 校验 Authorization 头、请求体不含 API key、429→quota 映射（`:915-970`）；`waitFor` 超时是抛错而不是返回 false（`cdp.mjs:164-185`）。
6. **更新链路有白名单 + 完整性校验**：`app:downloadUpdate` 只接受 `https://github.com/ParticleLight/ParticleBook/releases/download/` 前缀（`FileHandlers.cpp:342-346`），下载后按 latest.yml 的 sha512 校验（`:472-482`，与 release.yml 写 hex 小写一致），失败即删临时文件。
7. **ctest 不是空跑**：18 个目标全部 `add_test`，`test_assert.h` 的 `Summary()` 失败返回 1；sapi_tts_test 对环境差异（无音频设备/无中文音色）显式 SKIP 但不会把已失败的 CHECK 吞掉。
