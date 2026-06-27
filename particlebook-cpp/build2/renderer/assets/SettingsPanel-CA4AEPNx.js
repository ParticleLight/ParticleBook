import { u as useSettingsStore, j as jsxRuntimeExports } from "./index-BwmGFUpy.js";
function SettingsPanel({ onClose, format, isClosing }) {
  const {
    fontSize,
    setFontSize,
    fontFamily,
    setFontFamily,
    lineHeight,
    setLineHeight,
    margin,
    setMargin,
    textAlign,
    setTextAlign
  } = useSettingsStore();
  const isTextFormat = !format || !["pdf", "cbz", "cbr"].includes(format);
  const fonts = [
    { label: "衬线体", value: "Georgia, Noto Serif SC, serif" },
    { label: "无衬线", value: "Inter, Noto Sans SC, sans-serif" },
    { label: "等宽", value: "JetBrains Mono, monospace" }
  ];
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: `absolute inset-0 z-40 flex justify-end ${isClosing ? "animate-fade-out" : ""}`, children: [
    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: `absolute inset-0 bg-black/40 ${isClosing ? "opacity-0" : ""}`, style: { transition: "opacity 0.2s" }, onClick: onClose }),
    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: `relative w-80 h-full border-l border-[var(--reader-border)] shadow-2xl overflow-y-auto ${isClosing ? "animate-slide-out-right" : "animate-slide-in-right"}`, style: { backgroundColor: "var(--reader-sidebar)", opacity: 0.9 }, children: /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "p-6 space-y-6", children: [
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center justify-between", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("h2", { className: "text-lg font-semibold text-[var(--reader-text)]", children: "阅读设置" }),
        /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: onClose, className: "p-1 text-[var(--reader-text)] opacity-50 hover:opacity-80", children: /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-5 h-5", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M6 18L18 6M6 6l12 12" }) }) })
      ] }),
      isTextFormat && /* @__PURE__ */ jsxRuntimeExports.jsxs(jsxRuntimeExports.Fragment, { children: [
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("label", { className: "block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2", children: "字体" }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "flex flex-wrap gap-2", children: fonts.map((f) => /* @__PURE__ */ jsxRuntimeExports.jsx(
            "button",
            {
              onClick: () => setFontFamily(f.value),
              className: `px-3 py-2 text-sm rounded-lg transition-colors ${fontFamily === f.value ? "bg-[var(--reader-accent)] text-white" : "bg-[var(--reader-border)] text-[var(--reader-text)] opacity-70 hover:opacity-100"}`,
              style: { fontFamily: f.value },
              children: f.label
            },
            f.value
          )) })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsxs("label", { className: "block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2", children: [
            "字号: ",
            fontSize,
            "px"
          ] }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-3", children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx(
              "button",
              {
                onClick: () => setFontSize(Math.max(12, fontSize - 1)),
                className: "px-3 py-1 bg-[var(--reader-border)] rounded-lg text-[var(--reader-text)] opacity-70 hover:opacity-100",
                children: "A-"
              }
            ),
            /* @__PURE__ */ jsxRuntimeExports.jsx(
              "input",
              {
                type: "range",
                min: "12",
                max: "32",
                value: fontSize,
                onChange: (e) => setFontSize(Number(e.target.value)),
                className: "flex-1",
                style: { accentColor: "var(--accent)" }
              }
            ),
            /* @__PURE__ */ jsxRuntimeExports.jsx(
              "button",
              {
                onClick: () => setFontSize(Math.min(32, fontSize + 1)),
                className: "px-3 py-1 bg-[var(--reader-border)] rounded-lg text-[var(--reader-text)] opacity-70 hover:opacity-100",
                children: "A+"
              }
            )
          ] })
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsxs("label", { className: "block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2", children: [
            "行距: ",
            lineHeight.toFixed(1)
          ] }),
          /* @__PURE__ */ jsxRuntimeExports.jsx(
            "input",
            {
              type: "range",
              min: "1.2",
              max: "3",
              step: "0.1",
              value: lineHeight,
              onChange: (e) => setLineHeight(Number(e.target.value)),
              className: "w-full",
              style: { accentColor: "var(--accent)" }
            }
          )
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsxs("label", { className: "block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2", children: [
            "边距: ",
            margin,
            "px"
          ] }),
          /* @__PURE__ */ jsxRuntimeExports.jsx(
            "input",
            {
              type: "range",
              min: "0",
              max: "100",
              value: margin,
              onChange: (e) => setMargin(Number(e.target.value)),
              className: "w-full",
              style: { accentColor: "var(--accent)" }
            }
          )
        ] }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { children: [
          /* @__PURE__ */ jsxRuntimeExports.jsx("label", { className: "block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2", children: "对齐方式" }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex gap-2", children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx(
              "button",
              {
                onClick: () => setTextAlign("left"),
                className: `flex-1 py-2 text-sm rounded-lg transition-colors ${textAlign === "left" ? "bg-[var(--reader-accent)] text-white" : "bg-[var(--reader-border)] text-[var(--reader-text)] opacity-70 hover:opacity-100"}`,
                children: "左对齐"
              }
            ),
            /* @__PURE__ */ jsxRuntimeExports.jsx(
              "button",
              {
                onClick: () => setTextAlign("justify"),
                className: `flex-1 py-2 text-sm rounded-lg transition-colors ${textAlign === "justify" ? "bg-[var(--reader-accent)] text-white" : "bg-[var(--reader-border)] text-[var(--reader-text)] opacity-70 hover:opacity-100"}`,
                children: "两端对齐"
              }
            )
          ] })
        ] })
      ] }),
      !isTextFormat && /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "text-sm text-[var(--reader-text)] opacity-50 bg-[var(--reader-bg)] rounded-lg p-4", children: [
        "PDF 和漫画格式使用固定版式，不支持调整字体和排版设置。可使用 ",
        /* @__PURE__ */ jsxRuntimeExports.jsx("kbd", { className: "px-1.5 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60", children: "+" }),
        " / ",
        /* @__PURE__ */ jsxRuntimeExports.jsx("kbd", { className: "px-1.5 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60", children: "-" }),
        " 键缩放。"
      ] }),
      /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "pt-4 border-t border-[var(--reader-border)]", children: [
        /* @__PURE__ */ jsxRuntimeExports.jsx("h3", { className: "text-sm font-medium text-[var(--reader-text)] opacity-60 mb-3", children: "快捷键" }),
        /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "space-y-2 text-xs text-[var(--reader-text)] opacity-50", children: [
          /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex justify-between", children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx("span", { children: "上一页" }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("kbd", { className: "px-2 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60", children: "←" })
          ] }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex justify-between", children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx("span", { children: "下一页" }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("kbd", { className: "px-2 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60", children: "→" })
          ] }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex justify-between", children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx("span", { children: "添加书签" }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("kbd", { className: "px-2 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60", children: "B" })
          ] }),
          /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex justify-between", children: [
            /* @__PURE__ */ jsxRuntimeExports.jsx("span", { children: "返回" }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("kbd", { className: "px-2 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60", children: "Esc" })
          ] })
        ] })
      ] })
    ] }) })
  ] });
}
export {
  SettingsPanel
};
