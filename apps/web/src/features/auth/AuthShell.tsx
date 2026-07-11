import type { ReactNode } from 'react'
import './auth.css'

interface AuthShellProps {
  title: string
  subtitle: string
  children: ReactNode
}

/** Shared chrome for the public auth pages: centered card, brand mark, heading. */
export default function AuthShell({ title, subtitle, children }: AuthShellProps) {
  return (
    <main className="auth-page">
      <div className="auth-card">
        <div className="auth-brand">
          <span className="brand-mark" aria-hidden="true">
            F
          </span>
          <span className="brand-name">Finora</span>
        </div>
        <div>
          <h1 className="auth-title">{title}</h1>
          <p className="auth-subtitle">{subtitle}</p>
        </div>
        {children}
      </div>
    </main>
  )
}
