import { useEffect, useState, useRef, useCallback } from 'react'
import { useReaderStore } from '../../stores/readerStore'
import type { Book } from '../../stores/libraryStore'

interface ComicRendererProps {
  book: Book
  content: Uint8Array
  bookId: number
}

interface ImageEntry {
  name: string
  getData: () => Promise<Blob>
}

const CACHE_RANGE = 2

export function ComicRenderer({ book, content, bookId }: ComicRendererProps) {
  const [totalPages, setTotalPages] = useState(0)
  const [currentIndex, setCurrentIndex] = useState(0)
  const [isLoading, setIsLoading] = useState(true)
  const [pageUrls, setPageUrls] = useState<Map<number, string>>(new Map())

  const entriesRef = useRef<ImageEntry[]>([])
  const cacheRef = useRef<Map<number, string>>(new Map())

  const progress = useReaderStore((s) => s.progress)
  const setProgress = useReaderStore((s) => s.setProgress)
  const saveProgress = useReaderStore((s) => s.saveProgress)
  const navigateTarget = useReaderStore((s) => s.navigateTarget)
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget)
  const turnPageDelta = useReaderStore((s) => s.turnPageDelta)
  const clearTurnPage = useReaderStore((s) => s.clearTurnPage)
  const seekTarget = useReaderStore((s) => s.seekTarget)
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget)

  // Load zip and extract file entries (not blobs)
  useEffect(() => {
    let cancelled = false
    const loadComic = async () => {
      try {
        const JSZip = (await import('jszip')).default
        const zip = await JSZip.loadAsync(content)
        if (cancelled) return

        const imageFiles: { name: string; file: any }[] = []
        zip.forEach((path, file) => {
          if (/\.(jpg|jpeg|png|gif|webp|bmp)$/i.test(path) && !file.dir) {
            imageFiles.push({ name: path, file })
          }
        })
        imageFiles.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))

        entriesRef.current = imageFiles.map((img) => ({
          name: img.name,
          getData: () => img.file.async('blob'),
        }))

        setTotalPages(imageFiles.length)
        setIsLoading(false)

        const startPage = progress.page || 0
        setCurrentIndex(startPage)
      } catch (e) {
        console.error('Failed to load comic:', e)
        setIsLoading(false)
      }
    }
    loadComic()

    return () => {
      cancelled = true
      cacheRef.current.forEach((url) => URL.revokeObjectURL(url))
      cacheRef.current.clear()
    }
  }, [content])

  // Load nearby pages and release distant ones
  const updateCache = useCallback(async (center: number) => {
    const entries = entriesRef.current
    const cache = cacheRef.current
    if (entries.length === 0) return

    const needed = new Set<number>()
    for (let i = Math.max(0, center - CACHE_RANGE); i <= Math.min(entries.length - 1, center + CACHE_RANGE); i++) {
      needed.add(i)
    }

    // Load missing pages
    for (const idx of needed) {
      if (!cache.has(idx)) {
        try {
          const blob = await entries[idx].getData()
          const url = URL.createObjectURL(blob)
          cache.set(idx, url)
        } catch {}
      }
    }

    // Release distant pages
    for (const [idx, url] of cache) {
      if (!needed.has(idx)) {
        URL.revokeObjectURL(url)
        cache.delete(idx)
      }
    }

    setPageUrls(new Map(cache))
  }, [])

  useEffect(() => {
    if (!isLoading && totalPages > 0) {
      updateCache(currentIndex)
    }
  }, [currentIndex, isLoading, totalPages, updateCache])

  useEffect(() => {
    if (!navigateTarget) return
    if (navigateTarget.page) setCurrentIndex(navigateTarget.page)
    clearNavigateTarget()
  }, [navigateTarget])

  useEffect(() => {
    if (turnPageDelta === null) return
    setCurrentIndex((i) => Math.max(0, Math.min(i + turnPageDelta, totalPages - 1)))
    clearTurnPage()
  }, [turnPageDelta])

  // Progress-bar seek (drag the ReaderControls range input)
  useEffect(() => {
    if (seekTarget === null || totalPages === 0) return
    const idx = Math.max(0, Math.min(Math.round((seekTarget / 100) * (totalPages - 1)), totalPages - 1))
    setCurrentIndex(idx)
    clearSeekTarget()
  }, [seekTarget, totalPages, clearSeekTarget])

  useEffect(() => {
    if (totalPages === 0) return
    const progressPercent = (currentIndex / totalPages) * 100
    setProgress({ progress: progressPercent, page: currentIndex })
    saveProgress()
  }, [currentIndex, totalPages])

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight' || e.key === ' ' || e.key === 'PageDown') {
        e.preventDefault()
        setCurrentIndex((i) => Math.min(i + 1, totalPages - 1))
      }
      if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
        setCurrentIndex((i) => Math.max(i - 1, 0))
      }
      if (e.key === 'Home') setCurrentIndex(0)
      if (e.key === 'End') setCurrentIndex(totalPages - 1)
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [totalPages])

  if (isLoading) {
    return (
      <div className="h-full flex items-center justify-center bg-[var(--reader-bg)]">
        <div className="text-center">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500 mx-auto mb-4"></div>
          <p className="text-[var(--reader-text)] opacity-50">正在解压漫画...</p>
        </div>
      </div>
    )
  }

  if (totalPages === 0) {
    return (
      <div className="h-full flex items-center justify-center bg-[var(--reader-bg)]">
        <p className="text-[var(--reader-text)] opacity-50">未找到图片</p>
      </div>
    )
  }

  // Show current page with nearby pages for smooth scrolling
  const pagesToShow: number[] = []
  for (let i = Math.max(0, currentIndex - 1); i <= Math.min(totalPages - 1, currentIndex + 1); i++) {
    pagesToShow.push(i)
  }

  return (
    <div className="h-full overflow-auto bg-[var(--reader-bg)] flex flex-col items-center">
      <div className="w-full max-w-4xl">
        {pagesToShow.map((index) => {
          const url = pageUrls.get(index)
          return url ? (
            <img
              key={index}
              src={url}
              alt={`第 ${index + 1} 页`}
              className="w-full"
            />
          ) : (
            <div key={index} className="w-full h-96 flex items-center justify-center">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500"></div>
            </div>
          )
        })}
      </div>

      {/* Page indicator */}
      <div className="fixed bottom-20 left-1/2 -translate-x-1/2 bg-black/60 backdrop-blur-sm text-white text-sm px-4 py-2 rounded-full">
        {currentIndex + 1} / {totalPages}
      </div>
    </div>
  )
}
