import { r as reactExports, i as useReaderStore, u as useSettingsStore, j as jsxRuntimeExports } from "./index-BwmGFUpy.js";
let savedZoom = 1;
function PdfRenderer({ book, content: _content, bookId }) {
  const containerRef = reactExports.useRef(null);
  const [totalPages, setTotalPages] = reactExports.useState(0);
  const [visiblePage, setVisiblePage] = reactExports.useState(1);
  const [pageBounds, setPageBounds] = reactExports.useState([]);
  const [pageImages, setPageImages] = reactExports.useState(/* @__PURE__ */ new Map());
  const [fitScale, setFitScale] = reactExports.useState(1);
  const [zoom, _setZoom] = reactExports.useState(savedZoom);
  const docIdRef = reactExports.useRef(null);
  const renderingPages = reactExports.useRef(/* @__PURE__ */ new Set());
  const zoomTimerRef = reactExports.useRef(null);
  const visiblePageRef = reactExports.useRef(1);
  visiblePageRef.current = visiblePage;
  const initialScrollDone = reactExports.useRef(false);
  const progress = useReaderStore((s) => s.progress);
  const setProgress = useReaderStore((s) => s.setProgress);
  const saveProgress = useReaderStore((s) => s.saveProgress);
  const navigateTarget = useReaderStore((s) => s.navigateTarget);
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget);
  const turnPageDelta = useReaderStore((s) => s.turnPageDelta);
  const clearTurnPage = useReaderStore((s) => s.clearTurnPage);
  const seekTarget = useReaderStore((s) => s.seekTarget);
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget);
  const searchQuery = useReaderStore((s) => s.searchQuery);
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex);
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches);
  const theme = useSettingsStore((s) => s.theme);
  const pdfPageMatchesRef = reactExports.useRef([]);
  reactExports.useEffect(() => {
    (async () => {
      try {
        const info = await window.electronAPI.pdfOpen(book.file_path);
        docIdRef.current = info.id;
        setTotalPages(info.pageCount);
        setPageBounds(info.pageBounds);
      } catch (e) {
        console.error("PDF open failed:", e);
      }
    })();
    return () => {
      if (docIdRef.current != null) {
        window.electronAPI.pdfClose(docIdRef.current);
        docIdRef.current = null;
      }
    };
  }, [book.file_path]);
  const setZoom = reactExports.useCallback((z) => {
    const next = typeof z === "function" ? z(savedZoom) : z;
    savedZoom = next;
    _setZoom(next);
    if (zoomTimerRef.current) clearTimeout(zoomTimerRef.current);
    zoomTimerRef.current = setTimeout(() => setPageImages(/* @__PURE__ */ new Map()), 300);
  }, []);
  reactExports.useEffect(() => {
    const el = containerRef.current;
    if (!el || pageBounds.length === 0) return;
    const dim = pageBounds[0];
    const compute = () => {
      const w = el.clientWidth;
      if (w > 0) setFitScale(w / dim.width * 0.85 * savedZoom);
    };
    compute();
    const ro = new ResizeObserver(compute);
    ro.observe(el);
    return () => ro.disconnect();
  }, [pageBounds, zoom]);
  const renderPage = reactExports.useCallback(async (pageNum) => {
    const docId = docIdRef.current;
    if (docId == null || renderingPages.current.has(pageNum)) return;
    if (pageImages.has(pageNum)) return;
    if (pageBounds.length === 0) return;
    renderingPages.current.add(pageNum);
    try {
      const bounds = pageBounds[pageNum - 1];
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = Math.round(bounds.width * fitScale * dpr);
      const h = Math.round(bounds.height * fitScale * dpr);
      const url = await window.electronAPI.pdfRenderPage(docId, pageNum - 1, w, h);
      if (url) {
        setPageImages((prev) => {
          const n = new Map(prev);
          n.set(pageNum, url);
          return n;
        });
      }
    } catch (e) {
      console.error("Page render failed:", pageNum, e);
    } finally {
      renderingPages.current.delete(pageNum);
    }
  }, [pageBounds, fitScale, pageImages]);
  reactExports.useEffect(() => {
    if (!containerRef.current || totalPages === 0) return;
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          const pageNum = Number(entry.target.getAttribute("data-page"));
          if (!pageNum) continue;
          setVisiblePage((prev) => {
            if (prev !== pageNum) {
              setProgress({ progress: pageNum / totalPages * 100, page: pageNum });
              if (initialScrollDone.current) saveProgress();
            }
            return pageNum;
          });
          renderPage(pageNum);
          if (pageNum > 1) renderPage(pageNum - 1);
          if (pageNum < totalPages) renderPage(pageNum + 1);
        }
      }
    }, { root: containerRef.current, threshold: 0.1 });
    const els = containerRef.current.querySelectorAll("[data-page]");
    els.forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [totalPages, renderPage, setProgress]);
  reactExports.useEffect(() => {
    if (!navigateTarget || !containerRef.current) return;
    const el = containerRef.current.querySelector(`[data-page="${navigateTarget.page}"]`);
    if (el) el.scrollIntoView({ behavior: "smooth" });
    clearNavigateTarget();
  }, [navigateTarget, clearNavigateTarget]);
  reactExports.useEffect(() => {
    if (turnPageDelta === null || !containerRef.current) return;
    const tp = Math.max(1, Math.min(visiblePage + turnPageDelta, totalPages));
    const el = containerRef.current.querySelector(`[data-page="${tp}"]`);
    if (el) el.scrollIntoView({ behavior: "smooth" });
    clearTurnPage();
  }, [turnPageDelta, visiblePage, totalPages, clearTurnPage]);
  reactExports.useEffect(() => {
    if (seekTarget === null || totalPages === 0 || !containerRef.current) return;
    const tp = Math.max(1, Math.round(seekTarget / 100 * totalPages));
    const el = containerRef.current.querySelector(`[data-page="${tp}"]`);
    if (el) el.scrollIntoView({ behavior: "smooth" });
    clearSeekTarget();
  }, [seekTarget, totalPages, clearSeekTarget]);
  reactExports.useEffect(() => {
    if (totalPages === 0 || !containerRef.current) return;
    requestAnimationFrame(() => {
      const targetPage = progress.page || 1;
      const el = containerRef.current?.querySelector(`[data-page="${targetPage}"]`);
      if (el) {
        el.scrollIntoView({ block: "start" });
        setTimeout(() => {
          initialScrollDone.current = true;
        }, 500);
      }
    });
  }, [totalPages, progress.page]);
  reactExports.useEffect(() => {
    const h = (e) => {
      if (e.key === "+" || e.key === "=") {
        e.preventDefault();
        setZoom((s) => Math.min(s + 0.25, 4));
      }
      if (e.key === "-") {
        e.preventDefault();
        setZoom((s) => Math.max(s - 0.25, 0.5));
      }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [setZoom]);
  reactExports.useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const h = (e) => {
      if (e.ctrlKey) {
        e.preventDefault();
        setZoom((s) => Math.max(0.5, Math.min(4, s + (e.deltaY > 0 ? -0.1 : 0.1))));
      }
    };
    el.addEventListener("wheel", h, { passive: false });
    return () => el.removeEventListener("wheel", h);
  }, [setZoom]);
  reactExports.useEffect(() => {
    if (!searchQuery || !searchQuery.trim() || docIdRef.current == null) {
      pdfPageMatchesRef.current = [];
      return;
    }
    const q = searchQuery.trim().toLowerCase();
    (async () => {
      try {
        const result = await window.electronAPI.pdfExtractText(docIdRef.current);
        if (!result?.pages) return;
        const matches = [];
        for (const p of result.pages) {
          if (p.text && p.text.toLowerCase().includes(q)) {
            matches.push(p.pageNum);
          }
        }
        pdfPageMatchesRef.current = matches;
        setSearchMatches(matches);
      } catch {
      }
    })();
  }, [searchQuery]);
  reactExports.useEffect(() => {
    const matches = pdfPageMatchesRef.current;
    if (matches.length === 0 || currentSearchIndex < 0 || currentSearchIndex >= matches.length) return;
    const targetPage = matches[currentSearchIndex] + 1;
    const el = containerRef.current?.querySelector(`[data-page="${targetPage}"]`);
    if (el) {
      el.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  }, [currentSearchIndex]);
  const filterStyle = theme === "dark" ? "invert(1) hue-rotate(180deg) brightness(0.9)" : theme === "sepia" ? "invert(1) hue-rotate(180deg) sepia(0.4) brightness(0.7)" : "none";
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { ref: containerRef, className: "h-full overflow-auto bg-[var(--reader-bg)]", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "flex flex-col items-center py-2", children: Array.from({ length: totalPages }, (_, i) => i + 1).map((pageNum) => {
      const bounds = pageBounds[pageNum - 1];
      const height = bounds ? bounds.height * fitScale : 1e3;
      const img = pageImages.get(pageNum);
      return /* @__PURE__ */ jsxRuntimeExports.jsx("div", { "data-page": pageNum, className: "relative py-2", style: { contentVisibility: "auto", containIntrinsicSize: `auto ${height}px` }, children: img ? /* @__PURE__ */ jsxRuntimeExports.jsx("img", { src: img, alt: `Page ${pageNum}`, style: { filter: filterStyle, width: "100%", height: `${height}px`, objectFit: "contain" } }) : /* @__PURE__ */ jsxRuntimeExports.jsx("div", { style: { width: "100%", height: `${height}px`, background: "var(--reader-bg)", flexShrink: 0 } }) }, pageNum);
    }) }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "fixed bottom-20 left-1/2 -translate-x-1/2 bg-black/60 backdrop-blur-sm text-white text-sm px-4 py-2 rounded-full z-10 pointer-events-none", children: [
      visiblePage,
      " / ",
      totalPages
    ] })
  ] });
}
export {
  PdfRenderer
};
