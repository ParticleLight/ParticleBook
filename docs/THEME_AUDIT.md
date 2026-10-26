# 主题 token 审计 + shared/ui 基元（PB-025-A）

> 日期：2026-10-26 · 范围：`src/renderer/**`（components + styles）
> 目的：盘点硬编码颜色 / 深色假设，给出可执行的 token 化建议；同时新增
> `src/renderer/components/ui/` 基元（Popover / Dialog / Toolbar / Switch / Field / List），
> 供后续把散落的弹层、开关、表单字段、列表逐步替换过来。
>
> 本轮**只新增**基元与 token，**不接线**任何 Reader / Settings 功能组件（避免回归）。

## 1. 结论摘要

- 主题体系已经存在且是好的：`:root`（暗）/ `.light` / `.sepia` 三套 CSS 变量 +
  语义色（`--color-*`）、材质（`--mica-bg` / `--acrylic-bg`）、阅读器别名
  （`--reader-*`）。绝大多数组件已经用 `var(--...)`。
- **真正会破坏主题的是少量 Tailwind 调色板类与字面色值**，集中在阅读器：
  `bg-gray-900`（2 处）、`border-gray-*/text-gray-*`（4 处）、`#fff`（8 处）、
  `bg-black/N` 遮罩（20 处）、`rgba(...)` 内联（33 处，多数是渲染器内容层，可接受）。
- 风险分级：
  | 级别 | 含义 | 数量级 |
  |---|---|---|
  | **高** | 亮色/护眼主题下会明显错色、可读性下降 | 3 处（`bg-gray-900` 加载页与高亮弹层） |
  | **中** | 深色假设但通常压在图片/封面上，视觉可接受；换成 token 更稳 | 20+ 处遮罩、8 处 `#fff` 按钮文字 |
  | **低** | 内容层（PDF 滤镜、高亮颜色、EPUB 主题覆盖）或品牌色 swatch，属于「数据」不是「主题」 | 30+ 处 |
- 新增基元全部只读 token（`--ui-*`、`--text-*`、`--border`、`--color-*`），无硬编码色值；
  `globals.css` 增加了 12 个 `--ui-*` 语义 token（三主题各一份）与 `.pb-*` 类。

## 2. 审计方法

```bash
cd src/renderer
grep -rEo 'bg-black/[0-9]+|text-white|bg-gray-[0-9]+|text-gray-[0-9]+|border-gray-[0-9]+|bg-white|rgba\(' --include=*.tsx --include=*.ts components \
  | awk -F: '{print $1}' | sort | uniq -c | sort -rn
grep -rn 'var(--reader-[a-z-]*, #' components   # 带 hex fallback 的 token
```

口径说明：**命中数 ≠ 缺陷数**。例如 EPUB 主题覆盖里的 `#ffffff` 是「正文底色」、
PDF 搜索高亮 `#ffd54f` 是内容标注色，这些是数据/内容层，不需要 token 化；
而 `bg-gray-900` 出现在 UI 骨架里，就必须换。

## 3. 明细（按文件）

| 位置 | 命中 | 风险 | 说明 / 建议 |
|---|---|---|---|
| `Reader/ReaderView.tsx:435` | `bg-gray-900` | **高** | 阅读器加载页写死深灰。亮色/护眼主题下闪深色。→ 改成 `style={{ background: 'var(--reader-bg)' }}`（同文件其它地方已经这么做） |
| `Reader/TextRenderer.tsx:373,380,387` | `bg-gray-900` + `border-gray-700` + `text-gray-400` | **高** | 选中文字后的高亮颜色弹层是深色写死；亮色主题下像另一套 UI。→ 换成 `var(--ui-surface-raised)` / `var(--border)` / `var(--text-secondary)`（或直接用新的 `Popover`） |
| `Reader/ReaderView.tsx`（11 处）、`Reader/ReaderControls.tsx`、`Library/BookCard.tsx` 等 | `bg-black/20..60` | 中 | 遮罩/悬浮按钮压在半透明内容上；暗色下没问题，亮色/护眼下降噪不足。→ 统一 `var(--ui-backdrop)` 或保留（压在封面上可接受），至少弹层遮罩统一用 `--ui-backdrop` |
| `Library/Library.tsx:324,327`、`UI/ConfirmDialog.tsx:29`、`UI/UpdateBanner.tsx:72,74`、`Reader/ReaderView.tsx:411` | `#fff` 作为文字色 | 中 | 都压在 `--accent` / `--color-red` / `--color-green` 上。这些 token 在亮色主题里是深色（如 `#4f46e5`/`#dc2626`），白字仍可读，但**护眼主题 accent=`#b8860b` 上白字对比度偏低**。→ 统一用 `var(--accent-text)`（已有 token，暗色是 `#0d0d0d`、亮色是 `#ffffff`） |
| `Reader/DictionaryPopup.tsx`（15 处）、`Library/ChangelogPanel.tsx:25` | `var(--reader-*, #hex)` | 中 | fallback 的 hex 与当前 token 实际值不一致（如 `--reader-sidebar:#1f2937` vs 实际 `#242424`）。token 一旦没定义就会退回一个「像旧版」的颜色。→ 删除 fallback，或把 fallback 改成与主题一致的变量 |
| `Reader/EpubRenderer.tsx:379`（`#ffffff`）、`Reader/PdfRenderer.tsx:78`（`#ffd54f`）、`PdfRenderer` 滤镜 rgba | 内容层色 | 低 | EPUB 正文底色 / PDF 搜索高亮 / 亮度滤镜，属于内容渲染，不是 UI 主题。保留 |
| `Library/StatisticsPage.tsx`（3 处 rgba）、`Reader/EpubRenderer.tsx`（6 处 rgba） | 图表 / 高亮 rgba | 低 | 图表系列色建议后续加 `--chart-*` token；高亮色是用户可选的数据色，保留 |
| `Library/BookCard.tsx:69` | 装饰渐变里的 `#fff` | 低 | 5% 透明度的纸张纹理，几乎不可见。保留即可 |
| `Settings/GlobalSettings.tsx:49-52` | 强调色 swatch hex | 低 | 这是「颜色预览」，必须显示真实色值。保留 |
| `UI/ConfirmDialog.tsx`、`Library/BookSourcePanel.tsx` 等手写 backdrop | `fixed inset-0 bg-black/60` 等 | 中 | 每个弹层自己实现遮罩/焦点/ESC。**这是接 `Dialog` 的首批候选**（见 §5） |

## 4. 新增 token（`globals.css`，三主题各一份）

| token | 暗色 | 亮色 | 护眼 | 用途 |
|---|---|---|---|---|
| `--ui-backdrop` | rgba(0,0,0,.55) | rgba(0,0,0,.35) | rgba(0,0,0,.55) | 模态遮罩 |
| `--ui-surface-raised` | #2b2b2b | #ffffff | #2b241c | 浮层/对话框底色 |
| `--ui-surface-sunken` | 白 3% | 黑 3% | 暖白 3% | 内嵌区域 |
| `--ui-input-bg` | var(--bg-tertiary) | var(--bg-tertiary) | var(--bg-tertiary) | 表单控件底 |
| `--ui-switch-off` | 白 16% | 黑 22% | 暖白 20% | 开关轨道（关） |
| `--ui-switch-on` | var(--accent) | var(--accent) | var(--accent) | 开关轨道（开） |
| `--ui-switch-thumb` | #fff | #fff | #fff | 开关滑块 |
| `--ui-list-hover` | var(--surface-hover) | 同左 | 同左 | 列表 hover |
| `--ui-list-selected` | var(--color-indigo-bg) | 同左 | 同左 | 列表选中 |
| `--ui-focus-ring` | var(--border-focus) | 同左 | 同左 | 键盘焦点环 |
| `--ui-danger` / `--ui-danger-bg` | var(--color-red*) | 同左 | 同左 | 错误/危险 |

配套类：`.pb-popover`、`.pb-dialog(-backdrop/-title/-description/-footer)`、`.pb-toolbar`、
`.pb-switch(-sm)`、`.pb-field(-label/-hint/-error/-required)`、`.pb-list(-item)`、
`.pb-focus-ring`（统一 `:focus-visible` 环）、`prefers-reduced-motion` 降级。

## 5. shared/ui 基元 API（本轮新增，**尚未接线**）

位置：`src/renderer/components/UI/`（`index.ts` 统一导出）。
> 注：任务写的是 `components/ui/**（新）`，但仓库已有 `components/UI/`（ConfirmDialog /
> ErrorBoundary / UpdateBanner）。Windows 文件系统大小写不敏感，两者是同一目录；为免在
> Windows CI 上出现 `ui/` 与 `UI/` 的大小写冲突，本轮把基元放进既有 `UI/`，不新建小写目录。

| 基元 | 关键 API | 无障碍 / 交互 |
|---|---|---|
| `Switch` | `checked, onChange, disabled, label?, size?, id?` | `role="switch"` + `aria-checked`；Space/Enter 原生触发 |
| `Field` | `label, children, hint?, error?, required?, htmlFor?` | 自动给子控件接 `id` / `aria-invalid` / `aria-describedby` / `aria-required`；错误 `role="alert"` |
| `Toolbar` | `label, children, orientation?` | `role="toolbar"` + 方向键 / Home / End 在可聚焦子元素间移动焦点 |
| `Popover` | `open, onOpenChange, trigger, children`（children 可传函数收 `close`）, `placement?, label?` | trigger 自动挂 `aria-haspopup/expanded`；ESC / 点外部关闭；打开聚焦面板、关闭还原焦点；视口内夹取定位；portal 到 body |
| `Dialog` | `open, onClose, title?, description?, children, footer?, size?, closeOnBackdrop?` | `role="dialog" aria-modal`；Tab 焦点循环；ESC；body 滚动锁；关闭还原焦点；portal |
| `List<T>` | `items, getKey, renderItem, onSelect?, selectedKey?, empty?, label?, maxHeight?` | 传 `onSelect` 时为 `listbox/option`（方向键 / Home / End / Enter / Space），否则退化为 `list` |

设计约定：**组件不含任何硬编码色值**，结构性样式用 Tailwind，状态样式用 `.pb-*` 类 + `--ui-*` token；
后续接线时不要再用 `bg-black/60`、`#fff` 这类写法。

## 6. 建议的迁移顺序（后续任务）

1. **先修 3 处高风险**（`ReaderView` 加载页、`TextRenderer` 高亮弹层）——纯换 token，零行为变化。
2. 把 `#fff` 文字色统一成 `var(--accent-text)`（8 处，护眼主题受益）。
3. 手写 backdrop/焦点/ESC 的弹层（ConfirmDialog、DictionaryPopup、TranslationPopup、BookSourceManager 的三个弹层等）逐步换 `Dialog`；锚定式小面板换 `Popover`。
4. 设置页的 `settings-toggle` / 表单输入换 `Switch` / `Field`（可保留旧类作为兼容）。
5. 给图表加 `--chart-1..6` token（StatisticsPage）。
6. 清理 `var(--reader-*, #hex)` 的 fallback（DictionaryPopup / ChangelogPanel）。

## 7. 附：本轮验证

- `npx tsc --noEmit` 通过；`npm run build` 通过；未改任何 Reader/Settings 功能组件，未改行为。
- 基元为「新增、未引用」，因此不进首屏包（tree-shaken），对现有 UI 零影响。
