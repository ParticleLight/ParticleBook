// ── shared/ui（PB-025-A）────────────────────────────────────────────
// 统一导出的可复用基元。所有颜色/圆角/阴影走 globals.css 的 CSS 变量 token，
// 因此亮色 / 暗色 / 护眼主题自动适配；组件本身不含硬编码色值。
//
// 说明（2026-10-28 更新）：Switch 已接线（设置页开关、书源列表行内启用状态）；
// Field / List / Toolbar 仍是「已收口到 M3 令牌、待业务接线」的状态。
// 表单控件请直接用 globals.css 的 .m3-text-field / .m3-select.is-field /
// .m3-checkbox；表格用 .m3-table(+ .m3-table-wrap)。
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
// review U2/U5：弹层的「行为基座」（role/aria-modal/Esc/焦点陷阱/关闭回焦/逐层 Esc）。
// Dialog 内部也是它；新弹层请直接用 Modal，视觉类名由调用方传。
export { Modal } from './Modal'
export type { ModalProps } from './Modal'
export { List } from './List'
export type { ListProps, ListState } from './List'
export { M3Select } from './M3Select'
export type { M3SelectProps, M3SelectOption, M3SelectDataAttributes } from './M3Select'
