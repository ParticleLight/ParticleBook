# ParticleBook Android（开发中，未发布）

ParticleBook 的 Android 移植版。**Jetpack Compose + Material 3 原生界面**，与桌面端
（C++ Win32 + WebView2）完全独立；不加载桌面 React 前端，也不复用 Electron/WebView 外壳。

当前基线（2026-11-04，提交 `05a5b0c`）：**54 test suites / 255 tests / 0 failures**。
版本号 `2.2.1-p0`（`versionCode 1`），minSdk 26，targetSdk/compileSdk 35，仅 arm64-v8a + x86_64。
未上架 Google Play，属于内部开发阶段。

## 已实现

- **书架**：网格 / 列表、真实封面（PDF 首页 / EPUB OPF cover，内存 LRU + 磁盘缓存）、
  导入持久化（`filesDir/books.json` 原子写）、横屏列表 + NavigationRail、200% 字号布局。
- **阅读器**：PDF 原生渲染（`PdfRenderer`，页缓存按字节 LRU、退出释放、onTrimMemory 清理）；
  EPUB/HTML 仍走 WebView 兼容模式。
- **Z-Library（原生 HTTP，无 WebView）**：
  - 镜像三层（T0 官方族 / T1 动态 `zlib.wwwnav.com` / T2 兜底）+ 持久化、成功 TTL 12h、
    探测失败降权、rank + 延迟排序；`mirror_*` 日志可观测。
  - JS 反爬挑战求解（sha1 nonce）+ 桌面 Chrome UA + cookie 持久化。
  - 搜索（关键词/分页）、语言/格式/年份/排序筛选（A5）、书籍详情、书籍简介、
    同类推荐（A12）、登录/退出、忘记密码/注册外链（B12）。
  - 下载：`/dl` → 302/CDN、Range 续传、416 自愈、45s 零增长看门狗、取消保留 `.part`；
    完成自动入书架；下载记录页支持打开 / 定位 / 重新导入 / 删除。
  - 书单 / 榜单 / 分类发现页，全镜像不可达 banner。
- **设置**：下载目录（SAF `ACTION_OPEN_DOCUMENT_TREE`）、主题、字号、语言等。

## 构建与测试

前置：JDK 21、Android SDK（platform 35 / build-tools 35.0.0 / NDK 28.2.13676358 /
CMake 3.22.1）、Gradle 8.11.1。依赖已缓存在 `GRADLE_USER_HOME`（默认
`G:/Android/gradle-home`）时，仓库脚本默认离线构建。

```bash
# 在仓库根目录执行
bash scripts/android/build-debug.sh                    # debug APK（build 锁 + 离线）
bash scripts/android/spike2/build.sh android :app:assembleDebug   # 同上，底层入口
bash scripts/android/spike2/build.sh android :app:testDebugUnitTest  # JVM 单测
# 定向单测
bash scripts/android/spike2/build.sh android :app:testDebugUnitTest --tests 'app.particlebook.android.*'
# Release（debug keystore 签名，内部验收用）
bash scripts/android/build-release.sh
```

产物：

- debug：`android/app/build/outputs/apk/debug/app-debug.apk`
- release：`android/app/build/outputs/apk/release/app-release-signed.apk`

安装并启动（launcher 是 `MobileActivity`，不是 `MainActivity`）：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.particlebook/.android.MobileActivity
```

真机 debuggable 包可用 intent extra 固定镜像（离线 mock / 单镜像排查）：

```bash
adb shell am start -n app.particlebook/.android.MobileActivity \
  --es pb_zlib_mirror http://127.0.0.1:8899
```

## 目录结构

```text
android/
├─ app/                       # 应用模块（namespace/appId = app.particlebook）
│  ├─ src/main/java/app/particlebook/android/
│  │  ├─ MobileActivity.kt    # Compose 入口（launcher）
│  │  ├─ MainActivity.kt      # 旧 WebView 阅读器（EPUB/HTML 兼容模式）
│  │  ├─ ui/                  # Compose 屏幕：书架 / 找书 / 详情 / 下载 / 设置 / 阅读器 / 主题
│  │  ├─ zlib/                # Z-Library 原生层：镜像、挑战、搜索/详情/登录/下载解析
│  │  ├─ Bridge.kt / NativeCore.kt / Shim.kt  # 与桌面桥接约定对齐的兼容层
│  │  └─ Assets.kt            # seed 样书与 fixtures
│  ├─ src/main/cpp/           # 原生 PDF 相关 CMake 工程
│  ├─ src/main/assets/        # 离线资源（首页、seed 样书、fixtures）
│  └─ src/test/               # JVM 单测（54 suites / 255 tests）
├─ bench/                     # 性能基准辅助模块
├─ build.gradle.kts / settings.gradle.kts
└─ gradle.properties
```

## 开发约定

- 单测放在 `android/app/src/test/java/...`，只用 JVM 测试（不依赖设备 / 模拟器）。
- 网络层（`zlib/`）不依赖 Android UI，解析器用真实页面夹具驱动；新增站点结构变化时
  先补夹具 + 单测。
- 改 `.kt` 后至少跑 `bash scripts/android/spike2/build.sh android :app:testDebugUnitTest`。
- 构建脚本自带 build 锁（`scripts/android/spike2/lock.sh`），并行开发时不要绕过。
- 桌面端与安卓端的桥接契约以 `bridge/contract.mjs` 为唯一来源，不要手改生成的类型。

## 文档

设计、架构、验收报告与真机证据在 **本地** `docs/android-port/`（该目录被 gitignore，
不随仓库分发）；入口见 `docs/android-port/README.md`。其中：

- `architecture-v2.md`：分层架构与实测校准；
- `zlibrary-parity-plan.md`：与桌面端功能对齐矩阵；
- `team-round-20261104.md`：2026-11-04 八人团队任务 / 提交全景；
- `a5-a12-b12-acceptance.md`：A5 搜索筛选、A12 详情推荐、B12 登录外链验收口径。

## 已知限制

- 未发布，无正式签名 / 安装包分发流程；release 目前复用 debug keystore。
- 真机验收仍有待办：V9（200% 入口区纵向布局）复验、新功能真机回归、A13 在线阅读未做；
  A14/A15 非目标。
- 首次访问镜像需过 JS 挑战（约 1s）且必须桌面 Chrome UA；DiamWall 系镜像原生不可用。
- EPUB 兼容模式与原生阅读器进度暂不互通。
