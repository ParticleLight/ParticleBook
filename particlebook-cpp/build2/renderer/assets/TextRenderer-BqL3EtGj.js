import { r as reactExports, i as useReaderStore, u as useSettingsStore, j as jsxRuntimeExports, k as reactDomExports } from "./index-BwmGFUpy.js";
const HIGHLIGHT_COLORS = [
  { value: "rgba(234,179,8,0.45)", label: "黄" },
  { value: "rgba(59,130,246,0.45)", label: "蓝" },
  { value: "rgba(34,197,94,0.45)", label: "绿" },
  { value: "rgba(239,68,68,0.45)", label: "红" },
  { value: "rgba(168,85,247,0.45)", label: "紫" }
];
function TextRenderer({ book, content, bookId }) {
  const containerRef = reactExports.useRef(null);
  const text = reactExports.useMemo(() => new TextDecoder("utf-8").decode(content), [content]);
  const progress = useReaderStore((s) => s.progress);
  const setProgress = useReaderStore((s) => s.setProgress);
  const saveProgress = useReaderStore((s) => s.saveProgress);
  const setTableOfContents = useReaderStore((s) => s.setTableOfContents);
  const navigateTarget = useReaderStore((s) => s.navigateTarget);
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget);
  const turnPageDelta = useReaderStore((s) => s.turnPageDelta);
  const clearTurnPage = useReaderStore((s) => s.clearTurnPage);
  const seekTarget = useReaderStore((s) => s.seekTarget);
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget);
  const highlights = useReaderStore((s) => s.highlights);
  const addHighlight = useReaderStore((s) => s.addHighlight);
  const [highlightPopup, setHighlightPopup] = reactExports.useState(null);
  const searchQuery = useReaderStore((s) => s.searchQuery);
  const searchMatches = useReaderStore((s) => s.searchMatches);
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex);
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches);
  reactExports.useEffect(() => {
    const lines = text.split("\n");
    const chapterRe = /^第[一二三四五六七八九十百千\d]+[章节]/;
    const enChapterRe = /^Chapter\s+\d+/i;
    const potential = [];
    lines.forEach((line, i) => {
      const trimmed = line.trim();
      if (chapterRe.test(trimmed) || enChapterRe.test(trimmed)) {
        potential.push({ line: i, label: trimmed });
      }
    });
    let clusterEnd = -1;
    for (let j = 1; j < potential.length; j++) {
      if (potential[j].line - potential[j - 1].line <= 2) {
        clusterEnd = j;
      } else {
        break;
      }
    }
    const toc = potential.slice(clusterEnd + 1).filter((item) => item.label.length <= 20).map((item, i) => {
      let charOffset = 0;
      for (let k = 0; k < item.line; k++) charOffset += lines[k].length + 1;
      return { label: item.label, page: charOffset / text.length * 100, id: `toc-${charOffset}`, level: 2 };
    });
    setTableOfContents(toc);
  }, [text, setTableOfContents]);
  reactExports.useEffect(() => {
    if (!searchQuery || searchQuery.trim().length === 0) {
      if (searchMatches.length > 0) setSearchMatches([]);
      return;
    }
    const q = searchQuery.trim();
    let escaped = q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const matches = [];
    let re;
    try {
      re = new RegExp(escaped, "gi");
    } catch {
      return;
    }
    let m;
    while ((m = re.exec(text)) !== null) {
      matches.push(m.index);
    }
    setSearchMatches(matches);
  }, [searchQuery, text]);
  const fontSize = useSettingsStore((s) => s.fontSize);
  const fontFamily = useSettingsStore((s) => s.fontFamily);
  const lineHeight = useSettingsStore((s) => s.lineHeight);
  const margin = useSettingsStore((s) => s.margin);
  const textAlign = useSettingsStore((s) => s.textAlign);
  reactExports.useEffect(() => {
    if (!containerRef.current) return;
    const container = containerRef.current;
    const handleMouseUp = (e) => {
      setTimeout(() => {
        const selection = window.getSelection();
        if (!selection || selection.isCollapsed) return;
        const selectedText = selection.toString().trim();
        if (!selectedText || selectedText.length < 1) return;
        if (!container.contains(selection.anchorNode)) return;
        const contentDiv = container.querySelector(".reader-content") || container;
        const walker = document.createTreeWalker(contentDiv, NodeFilter.SHOW_TEXT);
        let curOff = 0;
        let selOff = -1;
        while (walker.nextNode()) {
          const node = walker.currentNode;
          if (node === selection.anchorNode) {
            selOff = curOff + selection.anchorOffset;
            break;
          }
          curOff += (node.textContent || "").length;
        }
        setHighlightPopup({ x: e.clientX, y: e.clientY, text: selectedText, offset: selOff >= 0 ? selOff : 0 });
      }, 0);
    };
    container.addEventListener("mouseup", handleMouseUp);
    return () => container.removeEventListener("mouseup", handleMouseUp);
  }, []);
  const handleAddHighlight = reactExports.useCallback((color) => {
    if (!highlightPopup) return;
    const { text: selectedText, offset } = highlightPopup;
    const page = offset / text.length * 100;
    addHighlight({ book_id: bookId, text: selectedText, color, page, cfi: `pos-${offset}` });
    setHighlightPopup(null);
  }, [highlightPopup, text, bookId, addHighlight]);
  const highlightedContent = reactExports.useMemo(() => {
    const ranges = [];
    for (const hl of highlights) {
      if (hl.cfi && String(hl.cfi).startsWith("pos-")) {
        const start = parseInt(String(hl.cfi).slice(4), 10);
        if (!isNaN(start) && start >= 0 && start < text.length) {
          const end = start + hl.text.length;
          if (text.slice(start, end) === hl.text) {
            ranges.push({ start, end, color: hl.color, type: "highlight" });
          }
        }
      } else {
        let pos = 0;
        while (pos < text.length) {
          const idx = text.indexOf(hl.text, pos);
          if (idx === -1) break;
          ranges.push({ start: idx, end: idx + hl.text.length, color: hl.color, type: "highlight" });
          pos = idx + hl.text.length;
        }
      }
    }
    if (searchQuery && searchMatches.length > 0) {
      const qLen = searchQuery.trim().length;
      for (let i = 0; i < searchMatches.length; i++) {
        const start = searchMatches[i];
        ranges.push({
          start,
          end: start + qLen,
          color: i === currentSearchIndex ? "rgba(251,191,36,0.55)" : "rgba(251,191,36,0.3)",
          type: i === currentSearchIndex ? "searchActive" : "search"
        });
      }
    }
    if (ranges.length === 0) return text;
    ranges.sort((a, b) => a.start - b.start);
    const merged = [];
    for (const r of ranges) {
      const last = merged[merged.length - 1];
      if (last && r.start <= last.end) {
        last.end = Math.max(last.end, r.end);
        if (r.type === "highlight" || r.type === "searchActive" && last.type === "search") {
          last.type = r.type;
          last.color = r.color;
        }
      } else {
        merged.push({ ...r });
      }
    }
    const parts = [];
    let lastEnd = 0;
    for (const r of merged) {
      if (r.start > lastEnd) parts.push(text.slice(lastEnd, r.start));
      const style = r.type === "highlight" ? { backgroundColor: r.color, borderRadius: "2px", color: "inherit" } : r.type === "searchActive" ? { backgroundColor: "rgba(251,191,36,0.55)", borderRadius: "2px", color: "inherit", outline: "2px solid rgba(251,191,36,0.8)", outlineOffset: "1px" } : { backgroundColor: "rgba(251,191,36,0.3)", borderRadius: "2px", color: "inherit" };
      parts.push(
        /* @__PURE__ */ jsxRuntimeExports.jsx("mark", { style, children: text.slice(r.start, r.end) }, `${r.start}`)
      );
      lastEnd = r.end;
    }
    if (lastEnd < text.length) parts.push(text.slice(lastEnd));
    return parts;
  }, [text, highlights, searchQuery, searchMatches, currentSearchIndex]);
  reactExports.useEffect(() => {
    if (!navigateTarget || !containerRef.current) return;
    const container = containerRef.current;
    let charOffset = -1;
    if (navigateTarget.cfi && String(navigateTarget.cfi).startsWith("toc-")) {
      const off = parseInt(String(navigateTarget.cfi).slice(4), 10);
      if (!isNaN(off)) charOffset = off;
    }
    if (charOffset >= 0) {
      const contentDiv = container.querySelector(".reader-content") || container;
      const walker = document.createTreeWalker(contentDiv, NodeFilter.SHOW_TEXT);
      let curOff = 0;
      let targetNode = null;
      let targetIdx = 0;
      while (walker.nextNode()) {
        const node = walker.currentNode;
        const len = (node.textContent || "").length;
        if (curOff + len > charOffset) {
          targetNode = node;
          targetIdx = charOffset - curOff;
          break;
        }
        curOff += len;
      }
      if (targetNode) {
        const range = document.createRange();
        range.setStart(targetNode, Math.min(targetIdx, (targetNode.textContent || "").length));
        range.collapse(true);
        const rect = range.getClientRects()[0];
        if (rect) {
          container.scrollTop += rect.top - container.getBoundingClientRect().top - 80;
        }
      }
    } else if (navigateTarget.page) {
      const maxScroll = container.scrollHeight - container.clientHeight;
      container.scrollTop = navigateTarget.page / 100 * maxScroll;
    }
    clearNavigateTarget();
  }, [navigateTarget]);
  reactExports.useEffect(() => {
    if (turnPageDelta === null || !containerRef.current) return;
    const container = containerRef.current;
    container.scrollBy({ top: turnPageDelta > 0 ? container.clientHeight : -container.clientHeight, behavior: "smooth" });
    clearTurnPage();
  }, [turnPageDelta]);
  reactExports.useEffect(() => {
    if (seekTarget === null || !containerRef.current) return;
    const container = containerRef.current;
    const scrollTop = seekTarget / 100 * (container.scrollHeight - container.clientHeight);
    container.scrollTop = scrollTop;
    clearSeekTarget();
  }, [seekTarget]);
  reactExports.useEffect(() => {
    if (searchMatches.length === 0 || currentSearchIndex < 0 || !containerRef.current) return;
    const offset = searchMatches[currentSearchIndex];
    if (offset === void 0) return;
    const container = containerRef.current;
    const contentDiv = container.querySelector(".reader-content") || container;
    const walker = document.createTreeWalker(contentDiv, NodeFilter.SHOW_TEXT);
    let curOff = 0;
    let targetNode = null;
    let targetIdx = 0;
    while (walker.nextNode()) {
      const node = walker.currentNode;
      const len = (node.textContent || "").length;
      if (curOff + len > offset) {
        targetNode = node;
        targetIdx = offset - curOff;
        break;
      }
      curOff += len;
    }
    if (targetNode) {
      const range = document.createRange();
      range.setStart(targetNode, Math.min(targetIdx, (targetNode.textContent || "").length));
      range.collapse(true);
      const rect = range.getClientRects()[0];
      if (rect) {
        container.scrollTop += rect.top - container.getBoundingClientRect().top - container.clientHeight / 3;
      }
    }
  }, [currentSearchIndex]);
  reactExports.useEffect(() => {
    if (!containerRef.current) return;
    const container = containerRef.current;
    let saveTimer = null;
    const handleScroll = () => {
      const scrollTop = container.scrollTop;
      const scrollHeight = container.scrollHeight - container.clientHeight;
      const progressPercent = scrollHeight > 0 ? scrollTop / scrollHeight * 100 : 0;
      setProgress({ progress: progressPercent, page: progressPercent, scrollPosition: scrollTop });
      if (saveTimer) clearTimeout(saveTimer);
      saveTimer = setTimeout(() => saveProgress(), 500);
    };
    container.addEventListener("scroll", handleScroll);
    if (progress.scrollPosition) {
      container.scrollTop = progress.scrollPosition;
    }
    return () => {
      container.removeEventListener("scroll", handleScroll);
      if (saveTimer) clearTimeout(saveTimer);
    };
  }, [text]);
  return /* @__PURE__ */ jsxRuntimeExports.jsxs(jsxRuntimeExports.Fragment, { children: [
    /* @__PURE__ */ jsxRuntimeExports.jsx(
      "div",
      {
        ref: containerRef,
        className: "h-full overflow-auto bg-[var(--reader-bg)] text-[var(--reader-text)]",
        style: { padding: `0 ${margin}px` },
        children: /* @__PURE__ */ jsxRuntimeExports.jsx(
          "div",
          {
            className: "reader-content select-text",
            style: {
              width: "100%",
              whiteSpace: "pre-line",
              overflowWrap: "break-word",
              fontSize: `${fontSize}px`,
              fontFamily: fontFamily.replace("Georgia", "JetBrains Mono").replace("serif", "monospace"),
              lineHeight,
              textAlign
            },
            children: highlightedContent
          }
        )
      }
    ),
    highlightPopup && reactDomExports.createPortal(
      /* @__PURE__ */ jsxRuntimeExports.jsxs(
        "div",
        {
          className: "fixed z-50 flex gap-2 bg-gray-900 border border-gray-700 rounded-lg shadow-xl p-2",
          style: { left: highlightPopup.x, top: highlightPopup.y - 44 },
          children: [
            HIGHLIGHT_COLORS.map((c) => /* @__PURE__ */ jsxRuntimeExports.jsx(
              "button",
              {
                onMouseDown: (e) => {
                  e.preventDefault();
                  handleAddHighlight(c.value);
                },
                className: "w-6 h-6 rounded-full border border-gray-600 hover:scale-110 transition-transform",
                style: { backgroundColor: c.value },
                title: c.label
              },
              c.value
            )),
            /* @__PURE__ */ jsxRuntimeExports.jsx(
              "button",
              {
                onMouseDown: (e) => {
                  e.preventDefault();
                  setHighlightPopup(null);
                },
                className: "w-6 h-6 rounded-full border border-gray-600 flex items-center justify-center text-gray-400 hover:text-white text-sm",
                children: "×"
              }
            )
          ]
        }
      ),
      document.body
    )
  ] });
}
export {
  TextRenderer
};
