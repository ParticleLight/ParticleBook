# ParticleBook

> **这是 C++ Win32 + WebView2 原生应用。Electron 版已废弃并从仓库移除。**
> ⚠️ **不要运行 `npm start` / `npm run dev`** —— 那些是已删除的 Electron 入口，跑起来是无图标旧版。

## 如何启动 / 构建

| 你想做的事 | 命令 |
|---|---|
| 安装前端依赖 | `npm install` |
| 构建 React 前端 | `npm run build` → 产出 `out/renderer/` |
| 启动 C++ 应用（需先构建） | `npm run start:cpp` |
| 一键重建前端+C++ | `npm run rebuild:cpp` |

C++ 后端构建需要 Visual Studio 2022 Build Tools + CMake 3.25+，用 NMake 生成器：

```bash
cd particlebook-cpp/build2
cmake .. -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=Release
# 在 "x64 Native Tools Command Prompt for VS 2022" 下，或调用 vcvarsall.bat x64：
nmake
```

`npm run build` 用 `electron-vite` 构建 React 前端到 `out/renderer`；CMake POST_BUILD 自动拷贝到 `particlebook-cpp/build2/renderer`。`electron-vite` 在此仅作前端构建工具，**不代表本应用是 Electron 应用**。

## 项目结构

```
src/renderer/               React 前端（C++ 版通过 WebView2 加载）
particlebook-cpp/          C++ Win32 + WebView2 后端
  src/App.cpp               工具栏注入、服务初始化
  src/WebViewHost.cpp       Win32 窗口 + WebView2
  src/BridgeServer.cpp      C++ ↔ JS 桥接（GenerateBridgeScript）
  src/services/             ZLibrary/Library/Pdf/BookSource/Database
particlebook-cpp/build2/    C++ 构建输出（gitignore）
out/                        前端构建输出（gitignore）
```

## 已删除的 Electron 残留

以下已从仓库移除，新对话不要尝试恢复或运行：
- `src/main/`（Electron 主进程）
- `electron-builder.json`、`start.bat`、`scripts/cleanup-locales.js`
- `release/`（旧 Electron 安装包 1.6~1.8）
- 依赖：`electron`、`electron-builder`、`electron-updater`、`cross-env`

更新检查走 C++ WinHTTP 直接拉 GitHub `latest.yml`，不依赖 electron-updater。
