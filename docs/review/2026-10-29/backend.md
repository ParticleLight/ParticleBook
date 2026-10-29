# C++ 后端与 Win32 外壳 review（2026-10-29）

范围：`particlebook-cpp/src/**`（handlers / services / platform / WebViewHost*.cpp / App.cpp / BridgeServer.cpp / main.cpp），HEAD=1feb175。
方法：只读静态审查（read / grep / glob / git_bash 只读命令），未构建、未运行。

## 概要（最该先修的三件事）

1. **手写 ZIP 解析器在中央目录遍历时没有边界校验**（`ZipReader.cpp:112-124`）：任何畸形 EPUB/DOCX（Z-Library 下载的书属于不可信输入）都能让导入线程越界读 46 字节结构体 + 最长 64KB 文件名，直接 access violation；同一函数还把清单里的 `centralDirSize`/`uncompSize` 直接拿去 malloc（最大 4GB）。
2. **书源下载链路完全没有网络超时，且用忙等等完成**（`BookSourceHttp.cpp:59`、`BookSourceContent.cpp:191`）：DNS 无响应时 WinHTTP 的 resolve 超时默认是「无限等待」，再配 `while (completed < total) Sleep(100)` 忙等，就是一个永不自愈的挂起，同时把 2~4 个工作线程永久占死，退出时 `~BookSourceService` join 会挂住。这个坑 Z-Library 下载侧已经踩过并修了（`WinHttpSetTimeouts`），书源侧漏了。
3. **`backup:restore` 会把备份清单里的 `sourcePath` 当绝对路径直接写**（`BackupService.cpp:1259-1267`）：一个手工构造的 .zip「备份」可以往任意盘符任意目录写任意字节（启动目录即可持久化），只挡了 `..` 与 UNC。

一条附带的、成本极低的性能隐患：`book:cover` 是**同步** handler（跑在 WebView2 UI 线程），封面缺失时会现场 spawn mutool 并等最多 30 秒，而且生成结果不写回 `cover_path` → 每次进书架都可能把整排 PDF 卡片重跑一遍。

## 发现

| ID | 严重度 | 位置 | 问题 | 影响 | 建议 |
|----|--------|------|------|------|------|
| B-01 | P0 | `utils/ZipReader.cpp:112-124` | 中央目录遍历只判 `pos < cdBuf.size()`，随后把 `cdBuf.data()+pos` 当 46 字节结构体读、并按 `nameLen`(≤65535) 从同处拷文件名 | 畸形/恶意 EPUB、DOCX 导入时堆越界读 → 崩溃（整进程）；也可能把堆内容当字符串带进后续逻辑 | 循环内先校验 `pos + sizeof(ZipCentralDirEntry) <= cdBuf.size()` 与 `pos + sizeof(...) + nameLen <= cdBuf.size()`，不满足就 break；用 memcpy 而不是 `reinterpret_cast` 读结构体 |
| B-02 | P1 | `services/BookSourceHttp.cpp:59-104` + `services/BookSourceContent.cpp:181-193` | `WinHttpOpen` 后从不 `WinHttpSetTimeouts`（resolve 默认无限）；`DownloadBook` 提交章节任务后 `while (completed < totalChapters) Sleep(100)` 无超时无取消 | 一条章节 URL 卡住 → 下载永久停在「下载中」，占死 `BookSourceService` 的 2~4 个工作线程（此后所有书源下载都不再出结果）；退出时 `~BookSourceService` 在 join 上阻塞，进程可能退不掉 | 加 `WinHttpSetTimeouts(hSession, 10000,10000,10000,30000)`；把忙等改成 `condition_variable::wait_for` + 总超时；失败/取消路径必须让 `completed` 前进（或用 `pending` 计数递减） |
| B-03 | P1 | `services/BackupService.cpp:1259-1267`（校验见 `:398-419`） | 恢复备份时只要 `IsSafeAbsoluteCoverPath(sourcePath)` 通过就 `WriteFileAtomicallyUtf8(cover.sourcePath, coverBytes)`；该函数只挡相对路径、UNC 与 `..`，任意 `X:\...` 都放行，内容与清单全部由 zip 提供 | 用户双击一个伪造的「备份」→ 任意文件写（如写到 Startup 目录）= 持久化/代码执行 | 默认关掉「写回原路径」（`restoreCoversToOriginalPath=false`），或只在路径位于应用数据目录 / 已知封面目录（`<UserData>/covers`、文档目录）下时才写回，并对 `sha256` 之外再加「必须是图片」的魔数校验 |
| B-04 | P1 | `handlers/FileHandlers.cpp:230-305`（生成逻辑 `services/LibraryService.cpp:259-289`） | `book:cover` 注册为**同步**方法（在 UI 线程执行）；`cover_path` 为空时会现场 `ExtractEpubMetadata`/`ExtractPdfCover` → `CreateProcessW(mutool)` + `WaitForSingleObject(30000)`；生成的封面路径**不写回数据库** | 书架每渲染一次就为每个无封面书重跑一遍子进程：单个 PDF 最坏卡 30 秒（窗口未响应），N 本叠加；mutool 卡住时既不 TerminateProcess 也不回收，句柄/进程泄漏 | 把 on-demand 封面生成挪到 JobService，结果立刻 `UpdateBookMetadata` 落库；或用 `IsCacheValid` 判断 `.cover.*` 已存在则跳过；`ExtractPdfCover` 超时分支补 `TerminateProcess` |
| B-05 | P2 | `services/ZLibraryService.cpp:199` + `services/ZLibraryMirror.cpp:321-348` | `zlib:fetchMirrors` 仍是**同步**桥接方法，内部 `FetchUrl("https://zz.ggonav.com/")`（超时 10s/20s）+ 对整页 HTML 跑两条 `std::regex`；`BridgeServer.cpp:58-66` 的注释自己写明「同步网络请求跑在 UI 线程 → 任何页面循环 invoke 就能让窗口未响应」，但只从外部白名单里删了它 | 应用页任何脚本（含将来误加的 UI 入口）调一次即可冻住 UI 20~30 秒；目前渲染层没有消费者，属于「留着的雷」 | 改成 async + JobService，或直接删掉注册（契约里同步降级），并保留外部白名单的收紧 |
| B-06 | P2 | `utils/ZipReader.cpp:100`、`:177-178` | `std::vector<uint8_t> cdBuf(eocd->centralDirSize)` 与 `destBuf(foundEntry->uncompSize)` 直接用压缩包内 32 位字段分配（最大 4GiB），未与文件实际大小/预算比对 | 一个几十 KB 的畸形 EPUB 可让导入线程尝试分配 4GB（vector 值初始化会真的触碰页面）→ 长时间卡顿/内存耗尽/坏分配 | 分配前夹紧：`centralDirSize <= fileSize`、`uncompSize <= 上限(如 256MB)`，超限直接判失败 |
| B-07 | P2 | `services/PdfService.cpp:293-300` | PDF 页面 PNG 缓存写进 `<exe>/renderer/_pb_pdf`，全仓没有任何清理（`_pb_files` 有 LRU，`_pb_pdf` 没有） | 长期使用后安装目录无限增长（一本 500 页 PDF 就是几百 MB，换设备/重装前不会回收） | 启动时或 `PdfService::Close` 后按 mtime/会话清一次，或复用 `_pb_files` 的 LRU 逻辑 |
| B-08 | P2 | `WebViewHost.cpp:313-355`、`:363-391` | `WM_ZLIB_*`/`WM_UPDATE_*` 用 `LPARAM` 传堆指针，只有「回调存在」分支才 `delete`；`WM_ZLIB_DOWNLOAD_DONE` 还要求 `wp == 1` | 回调未安装（SetupDownloadHandler 之前）或窗口先销毁导致 `PostMessage` 失败时，堆对象永久泄漏；发送侧（detached 线程）无法感知 | 统一改成「消息处理函数末尾无条件 `std::unique_ptr<T> guard(reinterpret_cast<T*>(lp))`」，或让发送侧负责在 PostMessage 失败时自行释放 |
| B-09 | P2 | `BridgeServer.cpp:153-155`、`:252-254` | `EmitEvent`/`SendResponse` 把 `this`（BridgeServer）capture 进投递队列，任务真正执行时才解引用 `m_webview` | 目前靠 `App::Shutdown` 的 `dispatcher->Stop()` 丢弃积压任务兜底，但任何新增的投递通道（或将来调整关停顺序）都会变成 UAF | 改成捕获 `std::weak_ptr<WebViewHost>`/`shared_ptr<ITaskDispatcher>` + 一个「已关闭」标志，不要捕裸 this |
| B-10 | P2 | `handlers/FileHandlers.cpp:255-258` | 封面 MIME 只区分 `.png` 与其它（其它一律 `image/jpeg`） | EPUB 里 `.gif`/`.webp`/`.svg` 封面被标成 jpeg → 卡片显示破图（data URL 的 MIME 不会被嗅探纠正） | 按扩展名映射 png/jpeg/gif/webp/svg，未知扩展返回 `application/octet-stream` 或先读魔数 |
| B-11 | P2 | `handlers/FileHandlers.cpp:634-681` | `book:writeDroppedFile` 是同步 handler：在 UI 线程上 `Base64Decode` 整本书再做一次落盘，且 `WriteFile(..., (DWORD)bytes.size())` | 拖一本 500MB 的 CBZ 进窗口 → UI 线程一次性吃掉 600MB+ 的字符串/解码缓冲（易 OOM/未响应）；>4GiB 静默截断 | 改成 JobService 异步；分块解码/落盘；用 `std::uint64_t` 累计并在超限时明确拒绝 |
| B-12 | P2 | `services/BookSourceContent.cpp:224-231` | 书源下载的落盘名只由书名派生，`CreateFileW(..., CREATE_ALWAYS)` | 同名不同书（不同书源）互相覆盖：库里 A 书的 `file_path` 内容被 B 书替换；删书时 `db:deleteBook` 也只清 `/dropped`，`/booksource` 残留 | 文件名带上 `bookUrl` 的 FNV-1a 哈希（与 `_pb_files`/`docx` 同一套做法），并在删书时清理 `/booksource` |
| B-13 | P2 | `services/LibraryService.cpp:213-225`、`handlers/FileFormatService.cpp:268-286` | `debug.log` 调试残留：`ExtractEpubMetadata` 每次调用都往 exe 同目录追加一行（含 `opfPath`/`coverFile`），`DebugLog` 每次开关文件一次；`book:import` 每个文件写 6~8 行 | 日志无上限增长并泄漏本地书路径；且 `book:cover` 走 UI 线程时也在做这件事（叠加 B-04 的阻塞） | 删掉这几处 `_wfopen(debug.log)`，统一走 `pb::LogDebug`（LogService 已有滚动与脱敏） |

## 详细分析

### B-01（P0）ZipReader 中央目录遍历越界读

触发路径：`book:import`（JobService worker）→ `LibraryService::FindOpfPath` / `ConvertDocxToHtml` → `ReadZipEntry`。书来自 Z-Library 下载或用户拖入，属于不可信输入。

```cpp
// utils/ZipReader.cpp:110-124
const ZipCentralDirEntry* foundEntry = nullptr;
size_t pos = 0;
for (uint16_t i = 0; i < eocd->totalEntries && pos < cdBuf.size(); i++) {
    auto* entry = reinterpret_cast<const ZipCentralDirEntry*>(cdBuf.data() + pos);   // 46 字节，无 pos+46<=size 校验
    if (entry->signature != 0x02014b50) break;

    std::string name(reinterpret_cast<const char*>(cdBuf.data() + pos + sizeof(ZipCentralDirEntry)),
                     entry->nameLen);                                                 // nameLen 可达 65535，无上界校验
```

`pos < cdBuf.size()` 只保证「还剩至少 1 字节」。`centralDirSize` 由包内 EOCD 决定，攻击者可以让它比真实中央目录小/大；随后 `pos` 会按 `sizeof+nameLen+extraLen+commentLen` 前进，很容易越过 `cdBuf` 末尾但仍满足 `pos < size`（例如只剩 10 字节）。此时读 `entry->signature` 已越界，读 `nameLen` 或拷文件名则可能越界 64KB → 访问违规（进程崩溃，导入的是书店的书——影响面不小）。
修复要点：先把 `ZipCentralDirEntry` memcpy 到本地结构体并逐项校验；`nameLen/extraLen/commentLen` 与剩余空间比对；任何不一致立即 return false（不要 clamp）。

### B-02（P1）书源 HTTP 无超时 + 无限忙等

```cpp
// services/BookSourceHttp.cpp:59-67
HINTERNET hSession = WinHttpOpen(L"ParticleBook/1.9",
    WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
if (!hSession) { ... return ""; }
// ← 这里缺 WinHttpSetTimeouts：resolve 默认 0 = 无限等待
HINTERNET hConnect = WinHttpConnect(hSession, whost.c_str(), ...);
```

```cpp
// services/BookSourceContent.cpp:190-193
// Wait for completion
while (completed < totalChapters) {
    Sleep(100);
}
```

`completed` 只在 `downloadChapter` 成功末尾自增；`FetchUrl` 卡住时它永不前进，于是这个循环永不退出。线程池只有 2~4 个线程（`BookSourceService.cpp:25-29`），且任务一旦入队就不会被取消，因此 2~4 次挂起之后「下载」功能整体死亡、UI 永久停在下载中。关停时 `~BookSourceService` 执行 `join`，若线程仍卡在 `WinHttpConnect`，进程退出会被拖住。
同样的坑在 `ZLibraryDownload.cpp:443` 已经修过（注释写明「WinHTTP 的 resolve 超时默认是 0 = 无限等待」），书源这一份没同步。

### B-03（P1）备份恢复可写任意绝对路径

```cpp
// services/BackupService.cpp:1259-1267
if (options.restoreCoversToOriginalPath &&
    IsSafeAbsoluteCoverPath(cover.sourcePath)) {
    std::string coverWriteCode;
    if (WriteFileAtomicallyUtf8(cover.sourcePath, coverBytes, coverWriteCode,
                                backup_codes::kIoError)) { placed = true; ... }
}
```

```cpp
// services/BackupService.cpp:398-419（节选）
bool IsSafeAbsoluteCoverPath(const std::string& path) {
    if (path.size() < 3 || path.size() > 30000) return false;
    if (!std::isalpha(...) || path[1] != ':' || (path[2] != '\\' && path[2] != '/')) return false;
    ... // 只拒绝 ".."，任何 X:\... 都返回 true
    return true;
}
```

`pb::RestoreOptions{}` 的默认值里 `restoreCovers = true`、`restoreCoversToOriginalPath = true`（`BackupService.h:187-200`），`BackupHandlers.cpp:155-156` 用的正是默认值。封面字节与 sha256 都在同一个 zip 的 manifest 里（攻击者可控），所以「校验通过」不构成保护。建议默认不写回原路径。

### B-04（P1）book:cover 同步 UI 线程 + 每次重跑 mutool

```cpp
// handlers/FileHandlers.cpp:230 + 269-282
bridge->RegisterMethod("book:cover", [db](const json& p) -> json {   // ← 同步方法 = UI 线程
    ...
    // On-demand cover generation
    if (format == "epub") { lib.ExtractEpubMetadata(filePath, em); ... }
    else if (format == "pdf") { newCover = lib.ExtractPdfCover(filePath); }   // 起子进程
```

```cpp
// services/LibraryService.cpp:276-286
if (CreateProcessW(nullptr, wCmdLine.data(), nullptr, nullptr, FALSE,
                   CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS,
                   nullptr, nullptr, &si, &pi)) {
    WaitForSingleObject(pi.hProcess, 30000);   // ← UI 线程等 30 秒；超时也不 TerminateProcess
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
}
```

生成出来的路径只作为返回值 base64 给前端，**没有回写 `book.cover_path`**（`FileImportService.cpp:260` 只在导入那一刻写），因此下一次 `book:cover` 还会再跑一遍 mutool。渲染层对每张书卡都会调（`BookCard.tsx:94`、`BookList.tsx:30`，统计页还有 TOP10 并发），所以「封面缺失的 PDF」= 每次进书架多次子进程 + 最坏 30s 级卡顿。

### B-05（P2）zlib:fetchMirrors 仍是同步网络接口

```cpp
// services/ZLibraryService.cpp:196-200
bridge->RegisterMethod("zlib:fetchMirrors",   [zlib](const json&)   { return zlib->FetchMirrors(); });
```

`FetchMirrors` 里 `FetchUrl("https://zz.ggonav.com/")`（`ZLibraryMirror.cpp:284` 设了 10s/20s 超时），随后对整页 HTML 跑 `std::regex`（`:340-348`）。`BridgeServer.cpp:58-66` 的注释已经把「这条是同步网络请求、跑在 UI 线程」列为从外部白名单移除它的理由，但它对**应用页**仍然可调用。当前没有消费者（`grep fetchMirrors src/renderer` 无命中），属于随时会被误用的雷。

### B-06（P2）Zip 清单里的尺寸直接拿去分配

```cpp
// utils/ZipReader.cpp:100
std::vector<uint8_t> cdBuf(eocd->centralDirSize);
// utils/ZipReader.cpp:177-183
size_t destLen = foundEntry->uncompSize;
std::vector<uint8_t> destBuf(destLen);
size_t written = tinfl_decompress_mem_to_mem(destBuf.data(), destLen, compBuf.data(), srcLen, 0);
```

两者都是包内 32 位字段（≤4GiB）。`cdBuf` 会被 `ReadFile` 写，`destBuf` 会被解压写，都会真的触碰页面 → 一个几 KB 的畸形 EPUB 即可造成长时间卡顿/内存耗尽。当前只靠 `catch (std::exception)` 兜底，失败后这本书被跳过，但代价是整机内存压力。

### B-07（P2）_pb_pdf 永不清理

```cpp
// services/PdfService.cpp:293-300
auto rendererDir = (std::filesystem::path(exePathBuf).parent_path() / "renderer" / "_pb_pdf").string();
std::filesystem::create_directories(pb::Utf8ToWide(rendererDir));
std::string fileName = "doc_" + std::to_string(id) + "_v" + version + "_page_" + ...;
```

`grep -R "_pb_pdf"` 全仓只有这两处（写入侧）。`_pb_files` 有「超 128 按 mtime 淘汰」，`_pb_pdf` 一个都没有；`~PdfService` 只清 `.mutool_tmp` 与 MOBI 补丁临时文件。

### B-08（P2）跨线程窗口消息的堆载荷没有统一所有权

```cpp
// WebViewHost.cpp:321-327（其余 WM_ZLIB_* 同构）
case WM_ZLIB_DOWNLOAD_PROGRESS:
    if (self->m_dlProgressCb) {          // ← 回调为空时 data 永不 delete
        auto* data = reinterpret_cast<DLProgressData*>(lp);
        self->m_dlProgressCb(data->fileName, data->received, data->total);
        delete data;
    }
    return 0;
```

发送侧（`ZLibraryDownload.cpp:521-528`、`FileHandlers.cpp:463/479/489` 等）在使用 `new` 之后只做 `PostMessage`；窗口已销毁时 `PostMessage` 返回失败，发送侧无法感知也没有回收。

### B-09（P2）EmitEvent/SendResponse 捕获裸 this

```cpp
// BridgeServer.cpp:153-155
m_webview->PostToUiThread([this, payload]() {
    if (m_webview) m_webview->PostMessageToRenderer(payload);   // ← 队列里排到执行时 this 可能已析构
});
```

目前安全的原因是 `App::Shutdown` 里 `m_webview->StopDispatcher()` 会清空队列（`UiDispatcher::Stop` 丢弃全部任务），而 `m_bridge.reset()` 在它之后。但这依赖一个跨三个文件的时序约定，且 `if (m_webview)` 只挡住了「webview 已置空」，挡不住 `this` 已释放——属于「下次改关停顺序就炸」的结构。建议捕获 `std::shared_ptr<ITaskDispatcher>` + `std::weak_ptr` 引用。

### B-10（P2）封面 MIME 只认 png

```cpp
// handlers/FileHandlers.cpp:255-258
std::string ext;
size_t dot = coverPath.rfind('.');
if (dot != std::string::npos) { ext = coverPath.substr(dot); for (auto& c : ext) c = (char)tolower(...); }
std::string prefix = "data:image/" + std::string(ext == ".png" ? "png" : "jpeg") + ";base64,";
```

EPUB 的 `coverFile` 完全可能指向 `gif/webp/svg`（`ExtractEpubCover` 会原样按源扩展名落盘），此时卡片是破图。

### B-11（P2）writeDroppedFile 在 UI 线程解码整本书

```cpp
// handlers/FileHandlers.cpp:649-676
std::vector<uint8_t> bytes = pb::Base64Decode(b64);        // UI 线程、整本书
...
WriteFile(hFile, bytes.data(), (DWORD)bytes.size(), &written, nullptr);   // 32 位截断
```

拖入大文件时 UI 线程被解码+落盘占住；`(DWORD)bytes.size()` 对 ≥4GiB 静默截断（随后 `written != bytes.size()` 会走删除分支，但期间已经产生一次巨大的内存峰值）。

### B-12（P2）书源下载的 txt 落盘名会互相覆盖

```cpp
// services/BookSourceContent.cpp:211-226
if (safeName.empty()) safeName = "book";
safeName += ".txt";
std::string dir = App::Instance().UserDataPath() + "/booksource";
...
HANDLE hFile = CreateFileW(pb::Utf8ToWide(filePath).c_str(), GENERIC_WRITE, 0, nullptr,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);   // ← 覆盖同名旧文件
```

库里已有该 `file_path` 时 `book:import` 会走「已存在则跳过」（`FileImportService.cpp:102-107`），但文件内容此时已经被覆盖了——A 书换成了 B 书的内容，而 A 的阅读进度/标注仍挂在 A 的 id 上。另外 `db:deleteBook` 只清 `<UserData>/dropped`（`DbHandlers.cpp:39-45`），`/booksource` 的文件会永久残留。

### B-13（P2）debug.log 调试残留

```cpp
// services/LibraryService.cpp:213-225（ExtractEpubMetadata 尾部）
auto logPath = std::filesystem::path(exePath).parent_path() / "debug.log";
FILE* lf = _wfopen(logPath.c_str(), L"a");
...
fprintf(lf, "[%s] ExtractEpubMetadata: opfPath=%s coverFile=%s\n", ...);
```

元数据抽取/导入/封面生成每一次调用都会写一次；无滚动、无脱敏，且写的是本地书库相对路径。应删掉，统一 `pb::LogDebug`（LogService 已具备滚动与 Redact）。

## 存疑 / 未覆盖

1. **`backup:restore` 的 Suspend 窗口期丢写**：`BackupHandlers.cpp:139` 先 `db->Suspend()`，恢复成功后 `onDone` 里才 `Resume()`→`Load()` 重新读盘。这期间任何 `db:updateProgress`/`db:updateSettings` 只会改内存（writer 已停），`Load` 一读盘就丢了。头文件注释写的是「no other DB call may run between them」（`DatabaseService.h:27-28`），但没有强制手段。验证方法：真机点「恢复备份」后立刻翻页/改设置，比对恢复后的 `reading_progress` 是否回退。
2. **`BridgeServer::InvokeMethod` 的五分钟阻塞**（`BridgeServer.cpp:205-207`）：`DoImport` 的 detached 线程若拿不到 respond（例如关停期 dispatcher 丢弃了 onDone），线程要空等 5 分钟。因进程随即退出，我没能证明有用户可见后果，故未列入发现表。验证方法：在导入进行时关窗口，观察进程是否立即退出。
3. **`search:build` 持锁整个构建**（`SearchIndexService.cpp:1428-1432`）：`Build` 全程持 `m_mutex`，期间 `search:query` 的 worker 只能排队。查询是异步 job，所以只影响响应延迟，未实测延迟量级。
4. **未覆盖**：`DictionaryService` / `ExportService` / `SearchIndexService` 的内部算法，`SettingsService` 归一化，`TranslationService` 的 HTTP/JSON 细节，`domain/Locator`、`domain/Annotation` 的序列化，`utils/mobi_patch.h`。这些只看了入口与锁，没有逐行读。
5. **`_pb_pdf` 的增长速率**是估算（按每页 PNG 100~300KB），未实测。

## 已核对且没问题的点

1. **JobService 的并发与关停**（`platform/JobService.cpp`）：`Stop()` 先置 `m_stopping`、搬空队列并对排队任务合成 `ok=false/cancelled=true/stopped` 的 onDone，再逐个 join（worker 自己调 Stop 时 detach 而非自 join）；`RunJob` 用 try/catch 把 work 的异常转成 `JobResult.error`；进度节流与「取消后不再刷进度」都在共享 `std::atomic_bool` 上做。没找到数据竞争。（副作用：非 `std::exception` 的异常会以 `"unknown exception"` 呈现并丢失原始类型，这属于可接受取舍。）
2. **DatabaseService 的落盘安全**（`services/DatabaseService.cpp:536-562`）：`WriteAtomic` 先写唯一临时文件、`f.fail()` 时删临时文件**不**替换正式文件，再 `MoveFileExW(MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)`；迁移前强制备份、迁移失败保留原文件；坏 JSON 先 `BackupCorruptFile` 再重置；schema 比本版本新时抛错拒绝打开（防降级）。`DeleteBook` 对 `reading_progress`（json object）用按键 `erase`，正确避开了 `docs/memory/pitfalls.md` 里记的 `remove_if` 毁数据坑。
3. **WebView2 消息来源判定**（`BridgeServer.cpp:41-71`）：`ClassifyOrigin` 对 `http://particlebook.app` 做的是精确前缀匹配（防 `particlebook.app.evil.com`），外部 http(s) 页只放行 3 个工具栏方法，`Unknown`（blob:/data:/about: → EPUB iframe）一律 return；`app:downloadUpdate` 另有「必须是官方 GitHub release 前缀」的二次校验（`FileHandlers.cpp:342-346`）。书正文走的是 `fetch(_pb_url)` 拿字节（`fileReader.ts:20`），不是把书页当文档导航，因此书页拿不到 App 源特权。
4. **下载链路的资源与校验**（`ZLibraryDownload.cpp:428-568`）：WinHTTP 句柄在每个提前返回分支都成对关闭；先写 `.part` 再 `MoveFileEx` 替换（避免重下同名书失败时毁掉旧文件）；读完分别判读失败/写失败/空响应/长度不足/HTML 登录页四道校验；`LooksLikeHtml` 只认 `<!doctype html` 与 `<html`，没误杀 FB2（XML）。
5. **单实例与关停顺序**（`App::Shutdown` + `SingleInstance`）：`m_single->Stop()` 在 `m_webview.reset()` 之前（pipe 线程不会碰到已释放的 WebViewHost）；`UiDispatcher::Stop` 会等另一线程上的 `Drain` 收尾再拆；全局 logger 用 `shared_ptr` + 互斥保护（`LogService.cpp:1516-1539`），detached 线程晚到的 `LogError` 不会 UAF。
6. **SAPI TTS 的线程/COM 规约**（`SapiTts.cpp`）：worker 与 notifier 两个线程各自 `CoInitializeEx(COINIT_MULTITHREADED)` 并在本线程 `CoUninitialize`，COM 对象在创建线程释放；`ShutdownLocked` 先 join notifier 再 join worker；异常一律在 `RunCommand`/`ConsumeEvents` 内吞掉，不会穿过消息循环。DWM 标题栏那套也对齐了 `pitfalls.md` 的结论（`sizeof(BOOL)` 的 cbAttribute、按 build 选 19/20、`SWP_FRAMECHANGED`、`ShowWindow` 前应用）。
