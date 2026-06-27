import { c as create, r as reactExports, j as jsxRuntimeExports } from "./index-BwmGFUpy.js";
const useBookSourceStore = create((set, get) => ({
  sources: [],
  isLoading: false,
  searchResults: [],
  isSearching: false,
  searchKeyword: "",
  searchPage: 1,
  searchError: null,
  downloadProgress: null,
  isDownloading: false,
  loadSources: async () => {
    set({ isLoading: true });
    try {
      const sources = await window.electronAPI.getBookSources();
      set({ sources });
    } catch (e) {
      console.error("Failed to load book sources:", e);
    } finally {
      set({ isLoading: false });
    }
  },
  importSources: async () => {
    const result = await window.electronAPI.importBookSources();
    await get().loadSources();
    return result;
  },
  toggleSource: async (id) => {
    await window.electronAPI.toggleBookSource(id);
    set({ sources: get().sources.map((s) => s.id === id ? { ...s, enabled: !s.enabled } : s) });
  },
  deleteSource: async (id) => {
    await window.electronAPI.deleteBookSource(id);
    set({ sources: get().sources.filter((s) => s.id !== id) });
  },
  clearAllSources: async () => {
    await window.electronAPI.clearAllBookSources();
    set({ sources: [] });
  },
  search: async (keyword, page = 1) => {
    set({ isSearching: true, searchKeyword: keyword, searchPage: page, searchError: null });
    try {
      const result = await window.electronAPI.searchBooks(keyword, page);
      if (result.error) {
        set({ searchResults: [], searchError: result.error });
      } else {
        set({ searchResults: result.results || [], searchError: null });
      }
    } catch (e) {
      console.error("Search failed:", e);
      set({ searchResults: [], searchError: e?.message || "搜索失败" });
    } finally {
      set({ isSearching: false });
    }
  },
  clearSearch: () => set({ searchResults: [], searchKeyword: "", searchPage: 1, searchError: null }),
  startDownload: async (sourceId, bookUrl, bookName, format = "txt") => {
    set({ isDownloading: true, downloadProgress: null });
    const unsubscribe = window.electronAPI.onDownloadProgress((progress) => {
      set({ downloadProgress: progress });
    });
    try {
      const bookId = await window.electronAPI.downloadBook(sourceId, bookUrl, bookName, format);
      return bookId;
    } finally {
      unsubscribe();
      set({ isDownloading: false });
    }
  },
  resetDownload: () => set({ downloadProgress: null, isDownloading: false })
}));
function BookSourcePanel({ onClose, isClosing }) {
  const [tab, setTab] = reactExports.useState("search");
  const {
    sources,
    isLoading,
    searchResults,
    isSearching,
    searchError,
    downloadProgress,
    isDownloading,
    loadSources,
    importSources,
    toggleSource,
    deleteSource,
    clearAllSources,
    search,
    startDownload,
    resetDownload
  } = useBookSourceStore();
  const [keyword, setKeyword] = reactExports.useState("");
  const [downloadingId, setDownloadingId] = reactExports.useState(null);
  reactExports.useEffect(() => {
    loadSources();
  }, []);
  const handleSearch = () => {
    if (keyword.trim()) search(keyword.trim());
  };
  const handleDownload = async (result) => {
    const key = `${result.sourceId}-${result.bookUrl}`;
    setDownloadingId(key);
    try {
      await startDownload(result.sourceId, result.bookUrl, result.name);
    } finally {
      setDownloadingId(null);
      setTimeout(() => resetDownload(), 3e3);
    }
  };
  const handleImport = async () => {
    const result = await importSources();
    if (result.total > 0) {
      alert(`成功导入 ${result.imported} 个书源（共 ${result.total} 个）`);
    }
  };
  const getProgressPercent = (p) => {
    if (p.total === 0) return 0;
    return Math.round(p.current / p.total * 100);
  };
  const getStatusText = (p) => {
    switch (p.status) {
      case "fetching_toc":
        return "正在获取目录...";
      case "downloading":
        return `下载中 ${p.current}/${p.total}${p.chapterName ? `: ${p.chapterName}` : ""}`;
      case "assembling":
        return "正在组装文件...";
      case "importing":
        return "正在导入书架...";
      case "done":
        return "下载完成！";
      case "error":
        return `下载失败: ${p.error}`;
      default:
        return "";
    }
  };
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "fixed inset-0 z-50 flex items-center justify-center animate-fade-in", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "absolute inset-0 bg-black/60", onClick: onClose }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: `relative bg-[var(--reader-bg)] rounded-2xl shadow-2xl w-[900px] max-h-[85vh] flex flex-col overflow-hidden border border-[var(--reader-border)] ${isClosing ? "animate-scale-out" : "animate-scale-in"}`, children: [
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center justify-between px-6 py-4 border-b border-[var(--reader-border)]", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("h2", { className: "text-lg font-bold text-[var(--reader-text)]", children: "书源管理" }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-3", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx(
            "button",
            {
              onClick: handleImport,
              className: "px-3 py-1.5 text-sm bg-indigo-600 text-white rounded-lg hover:bg-indigo-500 transition-colors",
              children: "导入 JSON"
            }
          ),
          /* @__PURE__ */ jsxRuntimeExports.jsx(
            "button",
            {
              onClick: onClose,
              className: "p-1.5 rounded-lg text-[var(--reader-text)] opacity-60 hover:opacity-100 hover:bg-[var(--reader-sidebar)] transition-colors",
              children: /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-5 h-5", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M6 18L18 6M6 6l12 12" }) })
            }
          )
        ] })
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex border-b border-[var(--reader-border)]", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx(
          "button",
          {
            onClick: () => setTab("search"),
            className: `px-6 py-3 text-sm font-medium transition-colors ${tab === "search" ? "border-b-2" : "text-[var(--reader-text)] opacity-50 hover:opacity-80"}`,
            style: tab === "search" ? { color: "var(--color-indigo)", borderColor: "var(--color-indigo)" } : void 0,
            children: "搜索"
          }
        ),
        /* @__PURE__ */ jsxRuntimeExports.jsxs(
          "button",
          {
            onClick: () => setTab("sources"),
            className: `px-6 py-3 text-sm font-medium transition-colors ${tab === "sources" ? "border-b-2" : "text-[var(--reader-text)] opacity-50 hover:opacity-80"}`,
            style: tab === "sources" ? { color: "var(--color-indigo)", borderColor: "var(--color-indigo)" } : void 0,
            children: [
              "源管理 (",
              sources.length,
              ")"
            ]
          }
        )
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "flex-1 overflow-y-auto p-6", children: tab === "search" ? /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-4", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex gap-2", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx(
            "input",
            {
              type: "text",
              value: keyword,
              onChange: (e) => setKeyword(e.target.value),
              onKeyDown: (e) => {
                if (e.key === "Enter") handleSearch();
              },
              placeholder: "搜索书名...",
              className: "flex-1 px-4 py-2.5 bg-[var(--reader-sidebar)] border border-[var(--reader-border)] rounded-lg text-sm text-[var(--reader-text)] placeholder-gray-500 focus:outline-none focus:border-indigo-500"
            }
          ),
          /* @__PURE__ */ jsxRuntimeExports.jsx(
            "button",
            {
              onClick: handleSearch,
              disabled: isSearching || !keyword.trim(),
              className: "px-6 py-2.5 bg-indigo-600 text-white rounded-lg hover:bg-indigo-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed text-sm font-medium",
              children: isSearching ? "搜索中..." : "搜索"
            }
          )
        ] }),
        downloadProgress && /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)] p-4", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center justify-between mb-2", children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-sm text-[var(--reader-text)]", children: getStatusText(downloadProgress) }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-sm text-[var(--reader-text)] opacity-60", children: downloadProgress.status === "downloading" ? `${getProgressPercent(downloadProgress)}%` : "" })
          ] }),
          downloadProgress.status === "downloading" && /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "h-2 bg-[var(--reader-border)] rounded-full overflow-hidden", children: /* @__PURE__ */ jsxRuntimeExports.jsx(
            "div",
            {
              className: "h-full bg-indigo-500 rounded-full transition-all duration-300",
              style: { width: `${getProgressPercent(downloadProgress)}%` }
            }
          ) }),
          downloadProgress.status === "done" && /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-sm mt-1", style: { color: "var(--color-green)" }, children: "已成功导入书架" })
        ] }),
        searchResults.length > 0 ? /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-2", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsxs("p", { className: "text-sm text-[var(--reader-text)] opacity-50", children: [
            "找到 ",
            searchResults.length,
            " 个结果"
          ] }),
          searchResults.map((result, i) => {
            const key = `${result.sourceId}-${result.bookUrl}`;
            const isItemDownloading = downloadingId === key;
            return /* @__PURE__ */ jsxRuntimeExports.jsxs(
              "div",
              {
                className: "flex items-center gap-4 p-3 bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)] hover:border-[var(--reader-text)] hover:border-opacity-20 transition-colors",
                children: [
                  /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "w-12 h-16 rounded-lg overflow-hidden flex-shrink-0 bg-[var(--reader-border)]", children: result.coverUrl ? /* @__PURE__ */ jsxRuntimeExports.jsx("img", { src: result.coverUrl, alt: result.name, className: "w-full h-full object-cover" }) : /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "w-full h-full flex items-center justify-center text-[var(--reader-text)] opacity-20 text-xs", children: /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-6 h-6", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 1, d: "M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" }) }) }) }),
                  /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex-1 min-w-0", children: [
                    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-sm text-[var(--reader-text)] font-medium truncate", children: result.name }),
                    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-xs text-[var(--reader-text)] opacity-50 truncate", children: [
                      result.author || "未知作者",
                      " · ",
                      result.sourceName
                    ] }),
                    result.lastChapter && /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-xs text-[var(--reader-text)] opacity-40 truncate mt-0.5", children: [
                      "最新: ",
                      result.lastChapter
                    ] })
                  ] }),
                  /* @__PURE__ */ jsxRuntimeExports.jsx(
                    "button",
                    {
                      onClick: () => handleDownload(result),
                      disabled: isDownloading,
                      className: "px-4 py-2 text-sm bg-indigo-600 text-white rounded-lg hover:bg-indigo-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex-shrink-0",
                      children: isItemDownloading ? "下载中..." : "下载"
                    }
                  )
                ]
              },
              `${key}-${i}`
            );
          })
        ] }) : !isSearching && keyword ? /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-center py-12 text-[var(--reader-text)] opacity-40", children: searchError ? /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-left max-w-lg mx-auto", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "font-medium mb-2", style: { color: "var(--color-red)" }, children: "搜索出错" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("pre", { className: "text-xs text-left whitespace-pre-wrap bg-[var(--reader-sidebar)] rounded-lg p-4 border border-[var(--reader-border)]", style: { color: "var(--color-red)", opacity: 0.8 }, children: searchError })
        ] }) : /* @__PURE__ */ jsxRuntimeExports.jsxs(jsxRuntimeExports.Fragment, { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "未找到结果" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-sm mt-1", children: "请检查关键词或启用更多书源" })
        ] }) }) : !keyword ? /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-center py-12 text-[var(--reader-text)] opacity-40", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-16 h-16 mx-auto mb-4 opacity-30", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 1, d: "M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" }) }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "输入书名开始搜索" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-sm mt-1", children: sources.filter((s) => s.enabled).length > 0 ? `当前有 ${sources.filter((s) => s.enabled).length} 个启用的书源` : '请先在"源管理"中导入并启用书源' })
        ] }) : null
      ] }) : (
        /* Sources management tab */
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-3", children: [
          sources.length > 0 && /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "flex justify-end", children: /* @__PURE__ */ jsxRuntimeExports.jsx(
            "button",
            {
              onClick: () => {
                if (confirm("确定清空所有书源？此操作不可撤销。")) clearAllSources();
              },
              className: "px-3 py-1.5 text-xs rounded-lg transition-colors",
              style: { color: "var(--color-red)" },
              children: "全部清空"
            }
          ) }),
          sources.length === 0 ? /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-center py-12 text-[var(--reader-text)] opacity-40", children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-16 h-16 mx-auto mb-4 opacity-30", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 1, d: "M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" }) }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "暂无书源" }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-sm mt-1", children: '点击右上角"导入 JSON"添加书源' })
          ] }) : sources.map((source) => /* @__PURE__ */ jsxRuntimeExports.jsxs(
            "div",
            {
              className: "flex items-center gap-4 p-3 bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)]",
              children: [
                /* @__PURE__ */ jsxRuntimeExports.jsx(
                  "button",
                  {
                    onClick: () => toggleSource(source.id),
                    className: `relative w-10 h-5 rounded-full transition-colors flex-shrink-0 ${source.enabled ? "bg-indigo-600" : "bg-[var(--reader-border)]"}`,
                    children: /* @__PURE__ */ jsxRuntimeExports.jsx(
                      "div",
                      {
                        className: `absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${source.enabled ? "translate-x-5" : "translate-x-0.5"}`
                      }
                    )
                  }
                ),
                /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex-1 min-w-0", children: [
                  /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-sm text-[var(--reader-text)] font-medium truncate", children: source.bookSourceName || "未命名" }),
                  /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-xs text-[var(--reader-text)] opacity-40 truncate", children: source.bookSourceUrl })
                ] }),
                /* @__PURE__ */ jsxRuntimeExports.jsx(
                  "span",
                  {
                    className: `text-xs px-2 py-0.5 rounded-full flex-shrink-0 ${source.enabled ? "" : "bg-[var(--reader-border)] text-[var(--reader-text)] opacity-40"}`,
                    style: source.enabled ? { color: "var(--color-green)", backgroundColor: "var(--color-green-bg)" } : void 0,
                    children: source.enabled ? "启用" : "禁用"
                  }
                ),
                /* @__PURE__ */ jsxRuntimeExports.jsx(
                  "button",
                  {
                    onClick: () => deleteSource(source.id),
                    className: "p-1.5 rounded-lg text-[var(--reader-text)] opacity-40 hover:opacity-100 transition-colors flex-shrink-0",
                    children: /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-4 h-4", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" }) })
                  }
                )
              ]
            },
            source.id
          ))
        ] })
      ) })
    ] })
  ] });
}
export {
  BookSourcePanel
};
