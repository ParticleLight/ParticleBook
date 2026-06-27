const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["./jszip.min-BD0b1CyC.js","./index-BwmGFUpy.js","./index-CYn9DEtX.css","./_commonjs-dynamic-modules-TGKdzP3c.js"])))=>i.map(i=>d[i]);
import { r as reactExports, i as useReaderStore, j as jsxRuntimeExports, _ as __vitePreload } from "./index-BwmGFUpy.js";
const CACHE_RANGE = 2;
function ComicRenderer({ book, content, bookId }) {
  const [totalPages, setTotalPages] = reactExports.useState(0);
  const [currentIndex, setCurrentIndex] = reactExports.useState(0);
  const [isLoading, setIsLoading] = reactExports.useState(true);
  const [pageUrls, setPageUrls] = reactExports.useState(/* @__PURE__ */ new Map());
  const entriesRef = reactExports.useRef([]);
  const cacheRef = reactExports.useRef(/* @__PURE__ */ new Map());
  const progress = useReaderStore((s) => s.progress);
  const setProgress = useReaderStore((s) => s.setProgress);
  const saveProgress = useReaderStore((s) => s.saveProgress);
  const navigateTarget = useReaderStore((s) => s.navigateTarget);
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget);
  const turnPageDelta = useReaderStore((s) => s.turnPageDelta);
  const clearTurnPage = useReaderStore((s) => s.clearTurnPage);
  reactExports.useEffect(() => {
    let cancelled = false;
    const loadComic = async () => {
      try {
        const JSZip = (await __vitePreload(async () => {
          const { default: __vite_default__ } = await import("./jszip.min-BD0b1CyC.js").then((n) => n.j);
          return { default: __vite_default__ };
        }, true ? __vite__mapDeps([0,1,2,3]) : void 0, import.meta.url)).default;
        const zip = await JSZip.loadAsync(content);
        if (cancelled) return;
        const imageFiles = [];
        zip.forEach((path, file) => {
          if (/\.(jpg|jpeg|png|gif|webp|bmp)$/i.test(path) && !file.dir) {
            imageFiles.push({ name: path, file });
          }
        });
        imageFiles.sort((a, b) => a.name.localeCompare(b.name, void 0, { numeric: true }));
        entriesRef.current = imageFiles.map((img) => ({
          name: img.name,
          getData: () => img.file.async("blob")
        }));
        setTotalPages(imageFiles.length);
        setIsLoading(false);
        const startPage = progress.page || 0;
        setCurrentIndex(startPage);
      } catch (e) {
        console.error("Failed to load comic:", e);
        setIsLoading(false);
      }
    };
    loadComic();
    return () => {
      cancelled = true;
      cacheRef.current.forEach((url) => URL.revokeObjectURL(url));
      cacheRef.current.clear();
    };
  }, [content]);
  const updateCache = reactExports.useCallback(async (center) => {
    const entries = entriesRef.current;
    const cache = cacheRef.current;
    if (entries.length === 0) return;
    const needed = /* @__PURE__ */ new Set();
    for (let i = Math.max(0, center - CACHE_RANGE); i <= Math.min(entries.length - 1, center + CACHE_RANGE); i++) {
      needed.add(i);
    }
    for (const idx of needed) {
      if (!cache.has(idx)) {
        try {
          const blob = await entries[idx].getData();
          const url = URL.createObjectURL(blob);
          cache.set(idx, url);
        } catch {
        }
      }
    }
    for (const [idx, url] of cache) {
      if (!needed.has(idx)) {
        URL.revokeObjectURL(url);
        cache.delete(idx);
      }
    }
    setPageUrls(new Map(cache));
  }, []);
  reactExports.useEffect(() => {
    if (!isLoading && totalPages > 0) {
      updateCache(currentIndex);
    }
  }, [currentIndex, isLoading, totalPages, updateCache]);
  reactExports.useEffect(() => {
    if (!navigateTarget) return;
    if (navigateTarget.page) setCurrentIndex(navigateTarget.page);
    clearNavigateTarget();
  }, [navigateTarget]);
  reactExports.useEffect(() => {
    if (turnPageDelta === null) return;
    setCurrentIndex((i) => Math.max(0, Math.min(i + turnPageDelta, totalPages - 1)));
    clearTurnPage();
  }, [turnPageDelta]);
  reactExports.useEffect(() => {
    if (totalPages === 0) return;
    const progressPercent = currentIndex / totalPages * 100;
    setProgress({ progress: progressPercent, page: currentIndex });
    saveProgress();
  }, [currentIndex, totalPages]);
  reactExports.useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === "ArrowRight" || e.key === " " || e.key === "PageDown") {
        e.preventDefault();
        setCurrentIndex((i) => Math.min(i + 1, totalPages - 1));
      }
      if (e.key === "ArrowLeft" || e.key === "PageUp") {
        setCurrentIndex((i) => Math.max(i - 1, 0));
      }
      if (e.key === "Home") setCurrentIndex(0);
      if (e.key === "End") setCurrentIndex(totalPages - 1);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [totalPages]);
  if (isLoading) {
    return /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "h-full flex items-center justify-center bg-[var(--reader-bg)]", children: /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-center", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500 mx-auto mb-4" }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-[var(--reader-text)] opacity-50", children: "正在解压漫画..." })
    ] }) });
  }
  if (totalPages === 0) {
    return /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "h-full flex items-center justify-center bg-[var(--reader-bg)]", children: /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-[var(--reader-text)] opacity-50", children: "未找到图片" }) });
  }
  const pagesToShow = [];
  for (let i = Math.max(0, currentIndex - 1); i <= Math.min(totalPages - 1, currentIndex + 1); i++) {
    pagesToShow.push(i);
  }
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "h-full overflow-auto bg-[var(--reader-bg)] flex flex-col items-center", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "w-full max-w-4xl", children: pagesToShow.map((index) => {
      const url = pageUrls.get(index);
      return url ? /* @__PURE__ */ jsxRuntimeExports.jsx(
        "img",
        {
          src: url,
          alt: `第 ${index + 1} 页`,
          className: "w-full"
        },
        index
      ) : /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "w-full h-96 flex items-center justify-center", children: /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500" }) }, index);
    }) }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "fixed bottom-20 left-1/2 -translate-x-1/2 bg-black/60 backdrop-blur-sm text-white text-sm px-4 py-2 rounded-full", children: [
      currentIndex + 1,
      " / ",
      totalPages
    ] })
  ] });
}
export {
  ComicRenderer
};
