import { r as reactExports, u as useSettingsStore, j as jsxRuntimeExports } from "./index-BwmGFUpy.js";
const NAV_ITEMS = [
  { key: "appearance", label: "外观", icon: "M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01" },
  { key: "reading", label: "阅读", icon: "M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" },
  { key: "library", label: "书架", icon: "M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" },
  { key: "zlibrary", label: "Z-Library", icon: "M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" },
  { key: "about", label: "关于", icon: "M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" }
];
const FONTS = [
  { label: "衬线体", value: "Georgia, Noto Serif SC, serif", preview: "Aa 宋体" },
  { label: "无衬线", value: "Inter, Noto Sans SC, sans-serif", preview: "Aa 黑体" },
  { label: "等宽", value: "JetBrains Mono, monospace", preview: "Aa 等宽" }
];
const THEMES = [
  { id: "light", label: "浅色", icon: "M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" },
  { id: "dark", label: "深色", icon: "M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" },
  { id: "sepia", label: "护眼", icon: "M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" }
];
const ACCENTS = [
  { id: "blue", label: "蓝色", color: "#60cdff" },
  { id: "purple", label: "紫色", color: "#c084fc" },
  { id: "green", label: "绿色", color: "#4ade80" },
  { id: "orange", label: "橙色", color: "#fb923c" }
];
const SORT_OPTIONS = [
  { value: "last_opened", label: "最近阅读" },
  { value: "added_at", label: "添加时间" },
  { value: "title", label: "书名" },
  { value: "author", label: "作者" }
];
function SvgIcon({ d, className = "w-5 h-5" }) {
  return /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className, fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", strokeWidth: 1.5, children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", d }) });
}
function Toggle({ value, onChange }) {
  return /* @__PURE__ */ jsxRuntimeExports.jsx(
    "button",
    {
      className: `settings-toggle ${value ? "active" : ""}`,
      onClick: () => onChange(!value),
      role: "switch",
      "aria-checked": value
    }
  );
}
function Segment({ options, value, onChange }) {
  return /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-segment", children: options.map((opt) => /* @__PURE__ */ jsxRuntimeExports.jsxs(
    "button",
    {
      className: `settings-segment-btn ${value === opt.id ? "active" : ""}`,
      onClick: () => onChange(opt.id),
      children: [
        opt.icon && /* @__PURE__ */ jsxRuntimeExports.jsx(SvgIcon, { d: opt.icon, className: "w-4 h-4 inline mr-1.5 -mt-0.5" }),
        opt.label
      ]
    },
    opt.id
  )) });
}
function SectionTitle({ children }) {
  return /* @__PURE__ */ jsxRuntimeExports.jsx("h3", { className: "text-[13px] font-semibold uppercase tracking-wider mb-3", style: { color: "var(--text-tertiary)" }, children });
}
function AppearancePage() {
  const {
    theme,
    setTheme,
    accentColor,
    setAccentColor,
    fontFamily,
    setFontFamily,
    fontSize,
    setFontSize,
    lineHeight,
    setLineHeight,
    margin,
    setMargin,
    textAlign,
    setTextAlign
  } = useSettingsStore();
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-6", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "外观主题" }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "grid grid-cols-3 gap-3", children: THEMES.map((t) => /* @__PURE__ */ jsxRuntimeExports.jsxs(
        "button",
        {
          onClick: () => setTheme(t.id),
          className: "relative flex flex-col items-center gap-2 p-4 rounded-xl border-2 transition-all",
          style: {
            borderColor: theme === t.id ? "var(--accent)" : "var(--border)",
            background: theme === t.id ? "var(--color-indigo-bg)" : "var(--surface)"
          },
          children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx(SvgIcon, { d: t.icon, className: "w-6 h-6" }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-sm font-medium", children: t.label }),
            theme === t.id && /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "absolute top-2 right-2 w-4 h-4 rounded-full flex items-center justify-center", style: { background: "var(--accent)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-2.5 h-2.5", fill: "none", viewBox: "0 0 24 24", stroke: "var(--accent-text)", strokeWidth: 3, children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", d: "M5 13l4 4L19 7" }) }) })
          ]
        },
        t.id
      )) })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "主题色" }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "flex gap-3", children: ACCENTS.map((a) => /* @__PURE__ */ jsxRuntimeExports.jsxs(
        "button",
        {
          onClick: () => setAccentColor(a.id),
          className: "flex items-center gap-2.5 px-4 py-2.5 rounded-lg border-2 transition-all",
          style: {
            borderColor: accentColor === a.id ? "var(--accent)" : "var(--border)",
            background: accentColor === a.id ? "var(--color-indigo-bg)" : "var(--surface)"
          },
          children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "w-4 h-4 rounded-full", style: { background: a.color } }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-sm", children: a.label })
          ]
        },
        a.id
      )) })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "字体" }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "flex gap-3", children: FONTS.map((f) => /* @__PURE__ */ jsxRuntimeExports.jsxs(
        "button",
        {
          onClick: () => setFontFamily(f.value),
          className: "flex-1 p-3 rounded-xl border-2 transition-all text-center",
          style: {
            borderColor: fontFamily === f.value ? "var(--accent)" : "var(--border)",
            background: fontFamily === f.value ? "var(--color-indigo-bg)" : "var(--surface)",
            fontFamily: f.value
          },
          children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-lg mb-1", children: f.preview }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "text-xs", style: { color: "var(--text-tertiary)", fontFamily: "inherit" }, children: f.label })
          ]
        },
        f.value
      )) })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "排版" }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "字号" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "调整正文字体大小" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-3", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: () => setFontSize(Math.max(12, fontSize - 1)), className: "btn-ghost px-2 py-1 text-sm font-bold rounded-md", children: "A−" }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("span", { className: "text-sm w-10 text-center font-mono", style: { color: "var(--text-secondary)" }, children: [
            fontSize,
            "px"
          ] }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: () => setFontSize(Math.min(32, fontSize + 1)), className: "btn-ghost px-2 py-1 text-sm font-bold rounded-md", children: "A+" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("input", { type: "range", min: 12, max: 32, value: fontSize, onChange: (e) => setFontSize(Number(e.target.value)), className: "w-28" })
        ] })
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "行距" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "调整行与行之间的距离" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-3", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-xs", style: { color: "var(--text-tertiary)" }, children: "紧凑" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("input", { type: "range", min: 1.2, max: 3, step: 0.1, value: lineHeight, onChange: (e) => setLineHeight(Number(e.target.value)), className: "w-28" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-xs", style: { color: "var(--text-tertiary)" }, children: "宽松" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-sm w-8 text-center font-mono", style: { color: "var(--text-secondary)" }, children: lineHeight.toFixed(1) })
        ] })
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "边距" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "页面两侧的留白宽度" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-3", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-xs", style: { color: "var(--text-tertiary)" }, children: "窄" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("input", { type: "range", min: 0, max: 100, value: margin, onChange: (e) => setMargin(Number(e.target.value)), className: "w-28" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-xs", style: { color: "var(--text-tertiary)" }, children: "宽" }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("span", { className: "text-sm w-10 text-center font-mono", style: { color: "var(--text-secondary)" }, children: [
            margin,
            "px"
          ] })
        ] })
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "对齐方式" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "段落文字的对齐方式" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx(
          Segment,
          {
            options: [
              { id: "left", label: "左对齐" },
              { id: "justify", label: "两端对齐" }
            ],
            value: textAlign,
            onChange: setTextAlign
          }
        )
      ] })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-xs leading-relaxed px-1", style: { color: "var(--text-tertiary)" }, children: "以上设置为全局默认值。阅读某本书时可在侧边栏单独调整，该书将使用独立设置。" })
  ] });
}
function ReadingPage() {
  const {
    pageTurnMode,
    setPageTurnMode,
    autoSaveProgress,
    setAutoSaveProgress,
    showReadingTime,
    setShowReadingTime
  } = useSettingsStore();
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-6", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "翻页" }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "翻页方式" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "选择点击翻页或滚动浏览" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx(
          Segment,
          {
            options: [
              { id: "click", label: "点击翻页" },
              { id: "scroll", label: "滚动浏览" }
            ],
            value: pageTurnMode,
            onChange: setPageTurnMode
          }
        )
      ] })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "进度与统计" }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "自动保存进度" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "退出阅读时自动记住阅读位置" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx(Toggle, { value: autoSaveProgress, onChange: setAutoSaveProgress })
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "显示阅读时间" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "在阅读页面右下角显示本次阅读时长" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx(Toggle, { value: showReadingTime, onChange: setShowReadingTime })
      ] })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "快捷键" }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "grid grid-cols-2 gap-x-8 gap-y-2.5", children: [
        ["上一页 / 下一页", "← →"],
        ["全书搜索", "Ctrl+F"],
        ["下一个搜索结果", "Enter"],
        ["上一个搜索结果", "Shift+Enter"],
        ["关闭搜索 / 返回", "Esc"],
        ["添加书签", "B"],
        ["缩放 (PDF)", "+ − 或 Ctrl+滚轮"],
        ["下一页 (PDF/漫画)", "Space"]
      ].map(([action, key]) => /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center justify-between py-1", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-sm", style: { color: "var(--text-secondary)" }, children: action }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("kbd", { className: "px-2 py-0.5 text-xs rounded-md font-mono", style: { background: "var(--bg-tertiary)", color: "var(--text-tertiary)", border: "1px solid var(--border)" }, children: key })
      ] }, action)) })
    ] })
  ] });
}
function LibraryPage() {
  const {
    defaultViewMode,
    setDefaultViewMode,
    defaultSortBy,
    setDefaultSortBy
  } = useSettingsStore();
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-6", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "显示" }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "默认视图" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "书架的默认展示方式" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx(
          Segment,
          {
            options: [
              { id: "grid", label: "网格" },
              { id: "list", label: "列表" }
            ],
            value: defaultViewMode,
            onChange: setDefaultViewMode
          }
        )
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "默认排序" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc", children: "新打开书架时的默认排序方式" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-segment", children: SORT_OPTIONS.map((opt) => /* @__PURE__ */ jsxRuntimeExports.jsx(
          "button",
          {
            className: `settings-segment-btn ${defaultSortBy === opt.value ? "active" : ""}`,
            onClick: () => setDefaultSortBy(opt.value),
            children: opt.label
          },
          opt.value
        )) })
      ] })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "书架管理" }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-sm leading-relaxed", style: { color: "var(--text-secondary)" }, children: "在书架页面可以创建书柜分组，将书籍拖入不同分类。右键点击书籍可查看详情或删除。支持拖放导入和文件对话框导入。" })
    ] })
  ] });
}
function ZLibraryPage() {
  const [downloadPath, setDownloadPath] = reactExports.useState("");
  reactExports.useEffect(() => {
    window.electronAPI.zlibGetDownloadPath().then((r) => {
      if (r?.path) setDownloadPath(r.path);
    });
  }, []);
  const handleChangePath = async () => {
    try {
      const result = await window.electronAPI.zlibPickDownloadFolder();
      if (result?.path) setDownloadPath(result.path);
    } catch {
    }
  };
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-6", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "下载设置" }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-row", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-label", children: "下载位置" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "settings-row-desc break-all", children: downloadPath || "加载中..." })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: handleChangePath, className: "btn-secondary text-sm", children: "更改" })
      ] })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "使用说明" }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-3 text-sm leading-relaxed", style: { color: "var(--text-secondary)" }, children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "• 内置 Z-Library 浏览器，可在应用内直接搜索、浏览和下载电子书" }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "• 下载完成后自动导入书架，无需手动操作" }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "• 底部工具栏支持前进/后退/刷新和线路切换" }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "• 如果某个线路无法访问，可切换到其他镜像线路" })
      ] })
    ] })
  ] });
}
function AboutPage() {
  const [appVersion, setAppVersion] = reactExports.useState(null);
  const [updateStatus, setUpdateStatus] = reactExports.useState("idle");
  const [updateInfo, setUpdateInfo] = reactExports.useState(null);
  const [downloadPercent, setDownloadPercent] = reactExports.useState(0);
  const [errorMessage, setErrorMessage] = reactExports.useState("");
  const idleTimerRef = reactExports.useRef(null);
  reactExports.useEffect(() => {
    window.electronAPI.getAppVersion().then((v) => setAppVersion(v));
    const unsubs = [
      window.electronAPI.onUpdateAvailable((info) => {
        setUpdateInfo(info);
        setUpdateStatus("available");
      }),
      window.electronAPI.onUpdateNotAvailable(() => {
        setUpdateStatus("not-available");
        if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
        idleTimerRef.current = setTimeout(() => {
          setUpdateStatus("idle");
        }, 3e3);
      }),
      window.electronAPI.onUpdateDownloaded(() => {
        setUpdateStatus("downloaded");
      }),
      window.electronAPI.onUpdateError((msg) => {
        setErrorMessage(msg);
        setUpdateStatus("error");
        if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
        idleTimerRef.current = setTimeout(() => {
          setUpdateStatus("idle");
        }, 5e3);
      }),
      window.electronAPI.onUpdateDownloadProgress((p) => {
        setUpdateStatus("downloading");
        setDownloadPercent(p.percent);
      })
    ];
    return () => {
      unsubs.forEach((u) => u());
      if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
    };
  }, []);
  const handleCheckUpdate = async () => {
    setUpdateStatus("checking");
    setErrorMessage("");
    try {
      const info = await window.electronAPI.checkUpdate();
      if (info?.version) {
        setUpdateInfo(info);
        setUpdateStatus("available");
      } else {
        setUpdateStatus("not-available");
        if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
        idleTimerRef.current = setTimeout(() => {
          setUpdateStatus("idle");
        }, 3e3);
      }
    } catch {
      setErrorMessage("检查更新失败");
      setUpdateStatus("error");
    }
  };
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-6", children: [
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-4 mb-4", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "w-14 h-14 rounded-xl flex items-center justify-center", style: { background: "var(--color-indigo-bg)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-8 h-8", fill: "none", viewBox: "0 0 24 24", stroke: "var(--color-indigo)", strokeWidth: 1.5, children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", d: "M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" }) }) }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("h3", { className: "text-xl font-bold", children: "ParticleBook" }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("p", { className: "text-sm", style: { color: "var(--text-tertiary)" }, children: [
            "v",
            appVersion || "..."
          ] })
        ] })
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-sm leading-relaxed", style: { color: "var(--text-secondary)" }, children: "内置 Z-Library 的全功能电子书阅读器，支持 EPUB、PDF、MOBI、TXT、FB2、CBZ/CBR、HTML、Markdown 等多种格式。" }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "flex flex-wrap gap-2 mt-4", children: ["EPUB", "PDF", "MOBI", "TXT", "FB2", "CBZ/CBR", "HTML", "Markdown"].map((fmt) => /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "px-2 py-1 text-xs rounded-md font-medium", style: { background: "var(--color-indigo-bg)", color: "var(--color-indigo)" }, children: fmt }, fmt)) })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "软件更新" }),
      updateStatus === "checking" && /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-2 p-3 rounded-lg mb-3", style: { background: "var(--notify-info-bg)", color: "var(--notify-info-text)" }, children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "animate-spin rounded-full h-4 w-4 border-b-2 border-current" }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-sm", children: "正在检查更新..." })
      ] }),
      updateStatus === "available" && updateInfo && /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "p-3 rounded-lg mb-3", style: { background: "var(--notify-success-bg)", color: "var(--notify-success-text)" }, children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("p", { className: "text-sm font-medium mb-2", children: [
          "发现新版本 v",
          updateInfo.version
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: () => {
          setUpdateStatus("downloading");
          window.electronAPI.downloadUpdate(updateInfo?.downloadUrl || "");
        }, className: "btn-primary text-xs px-3 py-1.5", children: "下载更新" })
      ] }),
      updateStatus === "downloading" && /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "p-3 rounded-lg mb-3", style: { background: "var(--notify-info-bg)", color: "var(--notify-info-text)" }, children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-2 mb-2", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "animate-spin rounded-full h-4 w-4 border-b-2 border-current" }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("span", { className: "text-sm", children: [
            "正在下载... ",
            Math.round(downloadPercent),
            "%"
          ] })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "h-1.5 rounded-full overflow-hidden", style: { background: "var(--border)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "h-full rounded-full transition-all", style: { width: `${downloadPercent}%`, background: "var(--accent)" } }) })
      ] }),
      updateStatus === "downloaded" && /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "p-3 rounded-lg mb-3", style: { background: "var(--notify-success-bg)", color: "var(--notify-success-text)" }, children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("p", { className: "text-sm font-medium mb-2", children: "更新已下载" }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: () => window.electronAPI.quitAndInstall(), className: "btn-primary text-xs px-3 py-1.5", children: "立即重启安装" })
      ] }),
      updateStatus === "not-available" && /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "p-3 rounded-lg mb-3 text-sm", style: { background: "var(--notify-success-bg)", color: "var(--notify-success-text)" }, children: "已是最新版本" }),
      updateStatus === "error" && /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "p-3 rounded-lg mb-3 text-sm", style: { background: "var(--notify-error-bg)", color: "var(--notify-error-text)" }, children: errorMessage }),
      (updateStatus === "idle" || updateStatus === "not-available" || updateStatus === "error") && /* @__PURE__ */ jsxRuntimeExports.jsxs("button", { onClick: handleCheckUpdate, className: "btn-secondary w-full flex items-center justify-center gap-2", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx(SvgIcon, { d: "M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15", className: "w-4 h-4" }),
        "检查更新"
      ] })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "settings-card", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx(SectionTitle, { children: "致谢" }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-sm leading-relaxed space-y-1", style: { color: "var(--text-secondary)" }, children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("p", { children: [
          "作者：",
          /* @__PURE__ */ jsxRuntimeExports.jsx("span", { style: { color: "var(--text-primary)" }, children: "ParticleLight" })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "框架：Electron + React + TypeScript + Zustand" }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "PDF 引擎：MuPDF" }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("p", { children: "许可证：MIT" })
      ] })
    ] })
  ] });
}
function GlobalSettings({ onBack }) {
  const [activeNav, setActiveNav] = reactExports.useState("appearance");
  const accentColor = useSettingsStore((s) => s.accentColor);
  reactExports.useEffect(() => {
    const root = document.documentElement;
    if (accentColor === "blue") {
      root.removeAttribute("data-accent");
    } else {
      root.setAttribute("data-accent", accentColor);
    }
  }, [accentColor]);
  const renderPage = () => {
    switch (activeNav) {
      case "appearance":
        return /* @__PURE__ */ jsxRuntimeExports.jsx(AppearancePage, {});
      case "reading":
        return /* @__PURE__ */ jsxRuntimeExports.jsx(ReadingPage, {});
      case "library":
        return /* @__PURE__ */ jsxRuntimeExports.jsx(LibraryPage, {});
      case "zlibrary":
        return /* @__PURE__ */ jsxRuntimeExports.jsx(ZLibraryPage, {});
      case "about":
        return /* @__PURE__ */ jsxRuntimeExports.jsx(AboutPage, {});
    }
  };
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "h-screen flex flex-col", style: { background: "var(--bg)" }, children: [
    /* @__PURE__ */ jsxRuntimeExports.jsxs("header", { className: "drag-region flex items-center gap-4 px-6 py-3.5 shrink-0", style: { borderBottom: "1px solid var(--border)" }, children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: onBack, className: "no-drag p-1.5 rounded-lg transition-colors hover:bg-[var(--surface-hover)]", style: { color: "var(--text-secondary)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx(SvgIcon, { d: "M15 19l-7-7 7-7" }) }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("h1", { className: "no-drag text-base font-semibold", style: { color: "var(--text-primary)" }, children: "设置" })
    ] }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex-1 flex min-h-0", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsx("nav", { className: "w-56 shrink-0 p-3 overflow-y-auto", style: { borderRight: "1px solid var(--border)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "space-y-0.5", children: NAV_ITEMS.map((item) => /* @__PURE__ */ jsxRuntimeExports.jsxs(
        "button",
        {
          onClick: () => setActiveNav(item.key),
          className: `settings-nav-item ${activeNav === item.key ? "active" : ""}`,
          style: { position: "relative" },
          children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx(SvgIcon, { d: item.icon, className: "w-[18px] h-[18px] shrink-0" }),
            item.label
          ]
        },
        item.key
      )) }) }),
      /* @__PURE__ */ jsxRuntimeExports.jsx("main", { className: "flex-1 overflow-y-auto p-8", children: /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "max-w-2xl", children: renderPage() }) })
    ] })
  ] });
}
export {
  GlobalSettings
};
