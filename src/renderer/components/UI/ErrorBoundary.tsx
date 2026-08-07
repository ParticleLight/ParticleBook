import { Component, type ReactNode } from 'react'
import { withTranslation, type WithTranslation } from 'react-i18next'

interface Props { children: ReactNode }
interface State { hasError: boolean; error: Error | null }

class ErrorBoundaryInner extends Component<Props & WithTranslation, State> {
  state: State = { hasError: false, error: null }
  static getDerivedStateFromError(error: Error): State { return { hasError: true, error } }
  componentDidCatch(error: Error) { console.error('ErrorBoundary caught:', error) }

  render() {
    const { t } = this.props
    if (this.state.hasError) {
      return (
        <div className="h-screen flex flex-col items-center justify-center gap-4 p-8" style={{ background: 'var(--bg)', color: 'var(--text-primary)' }}>
          <svg className="w-16 h-16 opacity-20" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" /></svg>
          <h2 className="text-lg font-semibold">{t('出了点问题')}</h2>
          <p className="text-sm max-w-md text-center" style={{ color: 'var(--text-secondary)' }}>{t('应用遇到了意外错误，请尝试重启软件。')}</p>
          <button onClick={() => this.setState({ hasError: false, error: null })} className="btn-primary mt-2">{t('重试')}</button>
          {this.state.error && <pre className="text-xs max-w-lg overflow-auto whitespace-pre-wrap break-all mt-4" style={{ color: 'var(--text-tertiary)' }}>{this.state.error.message}</pre>}
        </div>
      )
    }
    return this.props.children
  }
}

export const ErrorBoundary = withTranslation()(ErrorBoundaryInner)
