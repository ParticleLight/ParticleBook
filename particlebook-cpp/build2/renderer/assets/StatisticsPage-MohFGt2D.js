import { a as useLibraryStore, r as reactExports, j as jsxRuntimeExports, f as formatReadingTime, g as generatePdfPreview, b as generateCbzPreview, e as extractTextPreview } from "./index-BwmGFUpy.js";
function BookRow({ book, readingTime, progress }) {
  const [coverUrl, setCoverUrl] = reactExports.useState(null);
  const [textPreview, setTextPreview] = reactExports.useState(null);
  reactExports.useEffect(() => {
    let mounted = true;
    const load = async () => {
      const cover = await window.electronAPI.getCoverImage(book.id);
      if (!mounted) return;
      if (cover) {
        setCoverUrl(cover);
      } else if (book.format === "pdf") {
        const p = await generatePdfPreview(book.file_path);
        if (mounted && p) setCoverUrl(p);
      } else if (book.format === "cbz" || book.format === "cbr") {
        const p = await generateCbzPreview(book.file_path);
        if (mounted && p) setCoverUrl(p);
      } else {
        const p = await extractTextPreview(book.file_path);
        if (mounted) setTextPreview(p);
      }
    };
    load();
    return () => {
      mounted = false;
    };
  }, [book.id, book.file_path, book.format]);
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-3 py-2", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "w-10 h-14 rounded overflow-hidden flex-shrink-0", style: { background: "var(--bg-tertiary)" }, children: coverUrl ? /* @__PURE__ */ jsxRuntimeExports.jsx("img", { src: coverUrl, alt: book.title, className: "w-full h-full object-cover" }) : textPreview ? /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "w-full h-full flex flex-col p-1.5 relative overflow-hidden", style: { background: "linear-gradient(135deg, var(--color-amber-bg), var(--color-amber) / 0.15)" }, children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-[8px] font-medium truncate", style: { color: "var(--color-amber)" }, children: book.title }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-[7px] leading-[10px] break-all line-clamp-[4] mt-0.5", style: { color: "var(--text-secondary)" }, children: textPreview })
    ] }) : /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "w-full h-full flex items-center justify-center text-[10px]", style: { color: "var(--text-tertiary)" }, children: book.format.toUpperCase() }) }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex-1 min-w-0", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-sm truncate", style: { color: "var(--text-primary)" }, children: book.title }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-xs truncate", style: { color: "var(--text-tertiary)" }, children: book.author || "未知作者" }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "mt-1.5 h-1 rounded-full", style: { background: "var(--bg-tertiary)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "h-full rounded-full transition-all", style: { width: `${Math.min(100, Math.round(progress))}%`, background: "var(--accent)" } }) })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-right flex-shrink-0 w-24", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-sm", style: { color: "var(--text-secondary)" }, children: [
        Math.min(100, Math.round(progress * 10) / 10),
        "%"
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-xs", style: { color: "var(--text-tertiary)" }, children: readingTime > 0 ? formatReadingTime(readingTime) : "未阅读" })
    ] })
  ] });
}
function StatisticsPage({ onBack }) {
  const readingTimeMap = useLibraryStore((s) => s.readingTimeMap);
  const readingProgressMap = useLibraryStore((s) => s.readingProgressMap);
  const [allBooks, setAllBooks] = reactExports.useState([]);
  reactExports.useEffect(() => {
    window.electronAPI.getBooks().then((books) => setAllBooks(books));
  }, []);
  const totalTime = Object.values(readingTimeMap).reduce((sum, t) => sum + t, 0);
  const booksWithProgress = Object.keys(readingProgressMap).length;
  const bookStats = allBooks.map((book) => ({ book, readingTime: readingTimeMap[book.id] || 0, progress: readingProgressMap[book.id]?.progress || 0 })).sort((a, b) => b.readingTime - a.readingTime);
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "h-full flex flex-col", style: { background: "var(--bg)" }, children: [
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-3 px-6 py-4", style: { borderBottom: "1px solid var(--border)" }, children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: onBack, className: "p-1 rounded-md transition-colors", style: { color: "var(--text-tertiary)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-5 h-5", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M15 19l-7-7 7-7" }) }) }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-2", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-5 h-5", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", style: { color: "var(--accent)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" }) }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-lg font-semibold", style: { color: "var(--text-primary)" }, children: "阅读统计" })
      ] })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "grid grid-cols-3 gap-4 px-6 py-4", style: { borderBottom: "1px solid var(--border)" }, children: [
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-center", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-2xl font-bold", style: { color: "var(--accent)" }, children: allBooks.length }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-xs mt-1", style: { color: "var(--text-tertiary)" }, children: "总书籍" })
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-center", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-2xl font-bold", style: { color: "var(--color-green)" }, children: formatReadingTime(totalTime) }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-xs mt-1", style: { color: "var(--text-tertiary)" }, children: "总阅读时间" })
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-center", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-2xl font-bold", style: { color: "var(--color-yellow)" }, children: booksWithProgress }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-xs mt-1", style: { color: "var(--text-tertiary)" }, children: "已开始阅读" })
      ] })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "flex-1 overflow-y-auto px-6 py-3", children: bookStats.length === 0 ? /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-center py-8", style: { color: "var(--text-tertiary)" }, children: "暂无书籍" }) : /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "space-y-3", children: bookStats.map((item) => /* @__PURE__ */ jsxRuntimeExports.jsx(BookRow, { book: item.book, readingTime: item.readingTime, progress: item.progress }, item.book.id)) }) })
  ] });
}
export {
  StatisticsPage
};
