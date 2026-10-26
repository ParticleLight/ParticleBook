// ── shared/ui（PB-025-A）────────────────────────────────────────────
// 统一导出的可复用基元。所有颜色/圆角/阴影走 globals.css 的 CSS 变量 token，
// 因此亮色 / 暗色 / 护眼主题自动适配；组件本身不含硬编码色值。
//
// 说明：这些基元目前是「新增、未接线」——后续把 Reader/Settings 的弹层、
// 开关、表单字段逐步替换过来（见 docs/THEME_AUDIT.md 的迁移建议）。
export { Switch } from './Switch'
export type { SwitchProps } from './Switch'
export { Field } from './Field'
export type { FieldProps } from './Field'
export { Toolbar } from './Toolbar'
export type { ToolbarProps } from './Toolbar'
export { Popover } from './Popover'
export type { PopoverProps, PopoverPlacement } from './Popover'
export { Dialog } from './Dialog'
export type { DialogProps } from './Dialog'
export { List } from './List'
export type { ListProps, ListState } from './List'
