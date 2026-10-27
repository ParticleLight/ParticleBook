import { useTranslation } from 'react-i18next'
import { Dialog } from './Dialog'

interface ConfirmDialogProps {
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void
}

export function ConfirmDialog({ title, message, confirmText, cancelText, danger = false, onConfirm, onCancel }: ConfirmDialogProps) {
  const { t } = useTranslation()
  const confirm = confirmText ?? t('确认')
  const cancel = cancelText ?? t('取消')
  return (
    <Dialog
      open
      onClose={onCancel}
      title={title}
      size="sm"
      footer={(
        <>
          <button onClick={onCancel} className="m3-text-button">{cancel}</button>
          <button
            onClick={onConfirm}
            className={danger ? 'm3-filled-button' : 'm3-filled-button'}
            style={danger ? { background: 'var(--md-sys-color-error)', color: 'var(--md-sys-color-on-error)' } : undefined}
          >
            {confirm}
          </button>
        </>
      )}
    >
      <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>{message}</p>
    </Dialog>
  )
}
