import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'

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
  return createPortal(
    <div className="fixed inset-0 z-[9999] flex items-center justify-center animate-fade-in" onClick={onCancel} role="dialog" aria-modal="true">
      <div className="absolute inset-0" style={{ background: 'rgba(0,0,0,0.45)' }} />
      <div className="relative rounded-xl shadow-win-lg p-6 max-w-sm w-full mx-4 animate-scale-in"
        style={{ background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', border: '1px solid var(--acrylic-border)' }}
        onClick={(e) => e.stopPropagation()}>
        <h3 className="text-base font-semibold mb-2" style={{ color: 'var(--text-primary)' }}>{title}</h3>
        <p className="text-sm mb-6" style={{ color: 'var(--text-secondary)' }}>{message}</p>
        <div className="flex justify-end gap-2">
          <button onClick={onCancel} className="btn-secondary">{cancel}</button>
          <button onClick={onConfirm} className="btn-primary"
            style={danger ? { background: 'var(--color-red)', color: '#fff' } : undefined}>{confirm}</button>
        </div>
      </div>
    </div>,
    document.body
  )
}
