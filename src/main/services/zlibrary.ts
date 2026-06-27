import { app, BrowserWindow, WebContentsView, session, DownloadItem, net } from 'electron'
import { join } from 'path'
import { mkdirSync } from 'fs'
import { LibraryService } from './library'

const FALLBACK_MIRRORS = [
  'https://zh.101fbiwarning.ru/',
  'https://zh.zzz101.ru/',
  'https://zh.1lib.sk/',
  'https://zh.z-library.sk/',
  'https://zh.singlelogin.re/',
  'https://zh.singlelogin.rs/',
]

const MIRROR_INDEX_URL = 'https://zz.ggonav.com/'

export class ZLibraryService {
  private view: WebContentsView | null = null
  private mainWindow: BrowserWindow | null = null
  private zlibSession: Electron.Session
  private library: LibraryService
  private currentMirrorIndex = 0
  private mirrors = [...FALLBACK_MIRRORS]

  constructor(library: LibraryService) {
    this.library = library
    this.zlibSession = session.fromPartition('persist:zlibrary', { cache: true })

    this.zlibSession.on('will-download', (_event, item) => {
      this.handleDownload(item)
    })

    this.fetchMirrorList()
  }

  private fetchMirrorList(): Promise<void> {
    return new Promise((resolve) => {
      try {
        const request = net.request(MIRROR_INDEX_URL)
        let body = ''
        request.on('response', (response) => {
          response.on('data', (chunk: Buffer) => { body += chunk.toString() })
          response.on('end', () => {
            const urls = this.parseMirrorUrls(body)
            if (urls.length > 0) {
              this.mirrors = urls
              console.log('[ZLib] fetched mirrors:', urls)
            }
            resolve()
          })
          response.on('error', () => resolve())
        })
        request.on('error', () => resolve())
        request.end()
      } catch {
        resolve()
      }
    })
  }

  private parseMirrorUrls(html: string): string[] {
    const urls: string[] = []
    // Find all links that look like Z-Library mirrors
    const linkRegex = /href="(https?:\/\/[^"]+)"/gi
    let match
    while ((match = linkRegex.exec(html)) !== null) {
      const url = match[1]
      // Filter for likely Z-Library mirror domains
      if (url.includes('z-lib') || url.includes('1lib') || url.includes('zzz') ||
          url.includes('singlelogin') || url.includes('fbiwarning') || url.includes('zlibrary') ||
          url.includes('bookfi') || url.includes('z-lib.')) {
        const normalized = url.endsWith('/') ? url : url + '/'
        if (!urls.includes(normalized)) {
          urls.push(normalized)
        }
      }
    }
    return urls
  }

  private safeSend(channel: string, ...args: any[]) {
    try {
      if (this.mainWindow && !this.mainWindow.isDestroyed() && !this.mainWindow.webContents.isDestroyed()) {
        this.mainWindow.webContents.send(channel, ...args)
      }
    } catch {}
  }

  async show(mainWindow: BrowserWindow) {
    console.log('[ZLib] show called')
    // Fetch latest mirror list
    await this.fetchMirrorList()
    if (this.view) {
      console.log('[ZLib] hiding previous view')
      this.hide(mainWindow)
    }

    this.mainWindow = mainWindow

    console.log('[ZLib] creating WebContentsView')
    this.view = new WebContentsView({
      webPreferences: {
        session: this.zlibSession,
        contextIsolation: true,
        nodeIntegration: false,
      },
    })
    console.log('[ZLib] WebContentsView created')

    // Set initial bounds to full window before adding to view
    const [width, height] = mainWindow.getContentSize()
    this.view.setBounds({ x: 0, y: 40, width, height: height - 40 })

    console.log('[ZLib] adding child view')
    mainWindow.contentView.addChildView(this.view)
    console.log('[ZLib] child view added')

    this.view.webContents.on('did-finish-load', () => {
      try {
        const url = this.view?.webContents.getURL()
        if (url) this.safeSend('zlib:urlChanged', url)
      } catch {}
    })

    this.view.webContents.on('did-navigate', (_event, url) => {
      this.safeSend('zlib:urlChanged', url)
    })

    this.view.webContents.on('did-navigate-in-page', (_event, url) => {
      this.safeSend('zlib:urlChanged', url)
    })

    this.view.webContents.on('page-title-updated', (_event, title) => {
      this.safeSend('zlib:titleChanged', title)
    })

    this.view.webContents.on('did-fail-load', (_event, errorCode, errorDesc) => {
      this.safeSend('zlib:urlChanged', `加载失败: ${errorDesc} (${errorCode})`)
    })

    this.loadCurrentMirror()
  }

  private loadCurrentMirror() {
    try {
      if (!this.view || this.view.webContents.isDestroyed()) return
      const url = this.mirrors[this.currentMirrorIndex]
      console.log('[ZLib] loading mirror:', url)
      this.safeSend('zlib:mirrorChanged', { index: this.currentMirrorIndex, url, mirrors: this.mirrors })
      this.view.webContents.loadURL(url).catch((err) => {
        console.log('[ZLib] loadURL failed:', err?.message)
      })
    } catch (e) {
      console.log('[ZLib] loadCurrentMirror error:', e)
    }
  }

  switchMirror(index: number) {
    try {
      if (index >= 0 && index < this.mirrors.length) {
        this.currentMirrorIndex = index
        // Recreate view to avoid segfault from stale webContents
        if (this.mainWindow) {
          this.show(this.mainWindow)
        }
      }
    } catch {}
  }

  getMirrorInfo(): { index: number; url: string; mirrors: string[] } {
    return { index: this.currentMirrorIndex, url: this.mirrors[this.currentMirrorIndex], mirrors: this.mirrors }
  }

  hide(mainWindow: BrowserWindow) {
    if (this.view) {
      try {
        if (!mainWindow.isDestroyed()) {
          mainWindow.contentView.removeChildView(this.view)
        }
      } catch {}
      try {
        if (!this.view.webContents.isDestroyed()) {
          this.view.webContents.close()
        }
      } catch {}
      this.view = null
    }
  }

  goBack() {
    try {
      if (this.view && !this.view.webContents.isDestroyed() && this.view.webContents.canGoBack()) {
        this.view.webContents.goBack()
      }
    } catch {}
  }

  goForward() {
    try {
      if (this.view && !this.view.webContents.isDestroyed() && this.view.webContents.canGoForward()) {
        this.view.webContents.goForward()
      }
    } catch {}
  }

  reload() {
    try {
      if (this.view && !this.view.webContents.isDestroyed()) {
        this.view.webContents.reload()
      }
    } catch {}
  }

  getURL(): string {
    try {
      if (this.view && !this.view.webContents.isDestroyed()) {
        return this.view.webContents.getURL()
      }
    } catch {}
    return ''
  }

  setBounds(bounds: { x: number; y: number; width: number; height: number }) {
    if (this.view) {
      try {
        this.view.setBounds(bounds)
      } catch {}
    }
  }

  async logout() {
    await this.zlibSession.clearStorageData()
    await this.zlibSession.clearCache()
    this.loadCurrentMirror()
  }

  private async handleDownload(item: DownloadItem) {
    const fileName = item.getFilename()
    const downloadDir = join(app.getPath('downloads'), 'ParticleBook')
    mkdirSync(downloadDir, { recursive: true })
    const savePath = join(downloadDir, fileName)

    item.setSavePath(savePath)

    item.on('updated', (_event, state) => {
      if (state === 'progressing') {
        this.safeSend('zlib:downloadProgress', {
          received: item.getReceivedBytes(),
          total: item.getTotalBytes(),
          fileName,
        })
      }
    })

    item.once('done', async (_event, state) => {
      if (state === 'completed') {
        this.safeSend('zlib:downloadComplete', { fileName, path: savePath })
        try {
          await this.library.importBook(savePath)
          this.safeSend('zlib:importComplete', { fileName })
        } catch (e: any) {
          this.safeSend('zlib:importError', { fileName, error: e?.message })
        }
      } else {
        this.safeSend('zlib:downloadError', { fileName, state })
      }
    })
  }
}
