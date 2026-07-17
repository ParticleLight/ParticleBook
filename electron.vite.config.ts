import { defineConfig } from 'electron-vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

// 本项目现为 C++ Win32 + WebView2 原生应用（Electron 主进程/preload 已废弃并删除）。
// 仅保留 renderer 块用于构建 React 前端 → out/renderer，C++ 后端通过 CMake
// POST_BUILD 命令拷贝到 build2/renderer。electron-vite 在此仅作前端构建工具使用。
export default defineConfig({
  renderer: {
    root: resolve(__dirname, 'src/renderer'),
    build: {
      target: 'chrome130',
      rollupOptions: {
        input: {
          index: resolve(__dirname, 'src/renderer/index.html')
        }
      }
    },
    plugins: [react()],
    resolve: {
      alias: {
        '@renderer': resolve(__dirname, 'src/renderer')
      }
    }
  }
})
