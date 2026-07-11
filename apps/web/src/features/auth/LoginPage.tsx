import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import { AlertCircle, Eye, EyeOff } from 'lucide-react'
import { errorMessage } from '../../components/states'
import { ApiError } from '../../lib/api'
import { useCurrentUser, useLogin } from './api'
import './auth.css'

interface LocationState {
  from?: string
  sessionExpired?: boolean
}

export default function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const location = useLocation()
  const state = (location.state ?? {}) as LocationState
  const currentUser = useCurrentUser()
  const login = useLogin()

  // Already authenticated (e.g. direct visit to /login): go straight in.
  if (currentUser.data) {
    return <Navigate to={safeReturnPath(state.from)} replace />
  }

  function safeReturnPath(from: string | undefined): string {
    return from && from.startsWith('/') && !from.startsWith('//') ? from : '/dashboard'
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!email.trim() || !password) {
      setFormError('Informe e-mail e senha.')
      return
    }
    setFormError(null)
    login.mutate({ email: email.trim(), password })
  }

  const serverError =
    login.error instanceof ApiError && login.error.status === 401
      ? 'E-mail ou senha inválidos.'
      : login.error
        ? errorMessage(login.error)
        : null

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
          <h1 className="auth-title">Entrar</h1>
          <p className="auth-subtitle">Acesse suas finanças pessoais.</p>
        </div>

        {state.sessionExpired && (
          <p className="auth-info" role="status">
            Sua sessão expirou. Entre novamente para continuar.
          </p>
        )}

        <form className="auth-form" onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="login-email">E-mail</label>
            <input
              id="login-email"
              className="input"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="login-password">Senha</label>
            <div className="password-field">
              <input
                id="login-password"
                className="input"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
              <button
                type="button"
                className="btn btn-ghost btn-icon password-toggle"
                onClick={() => setShowPassword((value) => !value)}
                aria-label={showPassword ? 'Ocultar senha' : 'Mostrar senha'}
                aria-pressed={showPassword}
              >
                {showPassword ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
              </button>
            </div>
          </div>

          {(formError || serverError) && (
            <div className="auth-error" role="alert">
              <AlertCircle size={16} aria-hidden="true" />
              <span>{formError ?? serverError}</span>
            </div>
          )}

          <button type="submit" className="btn btn-primary" disabled={login.isPending}>
            {login.isPending ? 'Entrando…' : 'Entrar'}
          </button>
        </form>

        <p className="auth-switch">
          Ainda não tem conta? <Link to="/register">Criar conta</Link>
        </p>
      </div>
    </main>
  )
}
