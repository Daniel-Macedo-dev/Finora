import type { ReactNode } from 'react'
import { AlertCircle, Inbox } from 'lucide-react'
import { ApiError, NetworkError } from '../lib/api'
import './states.css'

interface EmptyStateProps {
  title: string
  description?: string
  action?: ReactNode
}

export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <div className="state-box" role="status">
      <Inbox className="state-icon" aria-hidden="true" />
      <h2 className="state-title">{title}</h2>
      {description && <p className="state-description">{description}</p>}
      {action && <div className="state-action">{action}</div>}
    </div>
  )
}

interface ErrorStateProps {
  error: unknown
  onRetry?: () => void
}

export function errorMessage(error: unknown): string {
  if (error instanceof NetworkError) {
    return error.message
  }
  if (error instanceof ApiError) {
    return error.message
  }
  return 'Ocorreu um erro inesperado.'
}

export function ErrorState({ error, onRetry }: ErrorStateProps) {
  return (
    <div className="state-box state-error" role="alert">
      <AlertCircle className="state-icon" aria-hidden="true" />
      <h2 className="state-title">Não foi possível carregar os dados</h2>
      <p className="state-description">{errorMessage(error)}</p>
      {onRetry && (
        <div className="state-action">
          <button type="button" className="btn btn-secondary" onClick={onRetry}>
            Tentar novamente
          </button>
        </div>
      )}
    </div>
  )
}

export function LoadingCards({ count = 3, height = 120 }: { count?: number; height?: number }) {
  return (
    <div className="loading-cards" role="status" aria-live="polite">
      <span className="visually-hidden">Carregando…</span>
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className="skeleton" style={{ height }} aria-hidden="true" />
      ))}
    </div>
  )
}
