# ParticleBook UI · Material 3 优化审计（2026-10-27）

## 1. 设计意图

- 平台：Windows 桌面（Win32 + WebView2），键盘/鼠标为主，窗口 1000–1600px。
- 主任务：在书库中找到并打开一本书；导入/管理是次任务；阅读界面保持安静、可沉浸。
- 信息架构：书库 = 顶部 app bar（身份/搜索/主操作）+ 左侧导航抽屉（书柜）+ 内容区（当前书柜标题/筛选/书籍集合）。
- M3 表达克制：只让「导入」「当前书柜/主题选中」「当前阅读工具」有较强表达；卡片、列表、工具栏保持平静。

## 2. 语义层

新增 --md-sys-* 语义角色（颜色/字阶/形状/状态/动效），映射现有品牌色 --accent，暗/亮/护眼三主题各自覆盖 surface 家族。

- 页面背景：surface
- 主要文本：on-surface，次要文本：on-surface-variant
- 主操作：primary + on-primary
- 选中容器：primary-container + on-primary-container
- 分隔线：outline-variant
- 错误：error-container + on-error-container
- 浮层：surface-container-high，阴影 elevation-2/3

## 3. 组件映射

| 位置 | M3 语义 |
|---|---|
| 书库顶部栏 | Top app bar + search bar + filled button（导入）+ icon buttons + segmented button（视图） |
| 书柜导航 | Navigation drawer / nav item，选中用 primary container + aria-current |
| 书籍网格 | Elevated/outlined card，封面 + 标题/作者/元数据；格式徽章为 primary badge |
| 书库筛选 | Filter chip（原生 select 包装为 M3 下拉触发器，激活态 primary container） |
| 空态 | Empty state：primary-container 图标 + filled button |
| 设置 | Top app bar + navigation drawer + surface-container-low section cards + segmented button + switch |
| 阅读工具栏 | Bottom surface container / toolbar，icon buttons + segmented theme + linear slider |
| TTS 条 | Surface container high card，icon buttons + select + slider |
| 翻译/词典/搜索/日志 | Dialog / surface-container-high 浮层，28px 圆角 |
| 确认/删除 | M3 Dialog（焦点陷阱、ESC、滚动锁、危险按钮 error 色） |
| 更新横幅 | primary-container + filled button + icon close |

## 4. 状态与反馈

- default / hover / pressed / focus-visible / selected / disabled 均有对应 token 状态层。
- 加载：M3 圆形进度（主色），书库/设置/阅读器一致。
- 错误：error container + 可读原因 + 关闭/重试路径；导入错误列表保留逐条原因。
- 空态：说明性标题 + 单一主操作。
- 菜单：m3-menu 表面 + 菜单项 hover；危险项 is-destructive。
- 选中：导航/主题/视图/工具按钮使用 primary container，并同时有 aria 状态（非仅颜色）。

## 5. 可访问性

- 图标按钮均有 aria-label / title；分屏、标注、导入等操作有可读名称。
- 书卡与列表行支持 Tab 焦点 + Enter/Space 打开；确认框使用共享 Dialog 的焦点循环。
- 焦点环统一 :focus-visible；减少动效媒体查询关闭动画。
- 搜索框、筛选 selects、导航、工具栏均有可读标签。
- 已知待改进：图标按钮视觉 40px（桌面指针场景），未全部达到 48px 触控目标；部分旧弹层仍使用自定义结构。

## 6. 动效

- 使用 150/240/360ms 的 M3 标准缓动；仅用于 hover/pressed、浮层进入、页面淡入。
- prefers-reduced-motion 下关闭淡入/缩放与过渡。

## 7. 自评（0/1/2）

| 类别 | 分数 | 说明 |
|---|---:|---|
| Task clarity | 2 | 书库标题/搜索/导入/筛选层级明确 |
| Information hierarchy | 2 | App bar → 书柜标题 → 筛选 → 卡片 |
| Component semantics | 2 | 主操作用 filled，次级 icon/text，选中用 container + aria |
| Token discipline | 2 | 核心新增界面走 --md-sys-*；旧基础设施保留兼容映射 |
| Adaptive behavior | 1 | 1194px 下主操作收为图标；更窄窗口与 rail 折叠未完整适配 |
| States & feedback | 1 | 核心状态齐全；旧弹层/菜单的 disabled/loading 覆盖不完整 |
| Accessibility | 1 | 名称/焦点/键盘/减少动效已覆盖；40px 目标与部分对比度需再收 |
| Expressive restraint | 2 | 仅主操作和选中态表达，阅读内容保持安静 |

无 0 分项；可发布为增量优化，但 Adaptive/Accessibility 仍列为后续 refinement。
