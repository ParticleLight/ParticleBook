import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

// ParticleBook is a C++ Win32 + WebView2 application: there is no Electron
// runtime, main process or preload script. This config builds ONLY the React
// renderer into out/renderer, which the C++ CMake POST_BUILD step copies to
// build2/renderer.
//
// Two settings are load-bearing and must not be dropped:
//   base: './'  — WebView2 serves index.html from the particlebook.app virtual
//                 host; Vite's default absolute '/assets/...' refs would not
//                 resolve there.
//   outDir      — must stay out/renderer; that exact path is what CMake and
//                 npm run rebuild:cpp / package copy from.
export default defineConfig({
  root: resolve(__dirname, 'src/renderer'),
  base: './',
  build: {
    target: 'chrome130',
    outDir: resolve(__dirname, 'out/renderer'),
    emptyOutDir: true,
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
})
