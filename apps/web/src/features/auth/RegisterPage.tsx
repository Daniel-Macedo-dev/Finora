import { useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { AlertCircle } from 'lucide-react'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { ApiError } from '../../lib/api'
import { useCurrentUser, useRegister } from './api'
import './auth.css'

interface FieldErrors {
  displayName?: string
  email?: string
  password?: string
  confirmPassword?: string
}

export default function RegisterPage() {
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})

  const currentUser = useCurrentUser()
  const register = useRegister()

  if (currentUser.data) {
    return <Navigate to="/dashboard" replace />
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const next: FieldErrors = {}
    if (!displayName.trim()) {
      next.displayName = 'Informe seu nome.'
    }
    if (!email.trim() || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) {
      next.email = 'Informe um e-mail válido.'
    }
    if (password.length < 8 || password.length > 72) {
      next.password = 'A senha deve ter entre 8 e 72 caracteres.'
    }
    if (confirmPassword !== password) {
      next.confirmPassword = 'As senhas não coincidem.'
    }
    setErrors(next)
    if (Object.keys(next).length > 0) {
      return
    }
    register.mutate({ displayName: displayName.trim(), email: email.trim(), password })
  }

  const serverError = register.error
    ? register.error instanceof ApiError && register.error.code === 'EMAIL_ALREADY_REGISTERED'
      ? 'Este e-mail já possui uma conta. Tente entrar.'
      : errorMessage(register.error)
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
          <h1 className="auth-title">Criar conta</h1>
          <p className="auth-subtitle">
            Comece a organizar suas finanças e planejar compras com segurança.
          </p>
        </div>

        <form className="auth-form" onSubmit={handleSubmit} noValidate>
          <FormField label="Nome" error={errors.displayName}>
            <input
              className="input"
              autoComplete="name"
              maxLength={100}
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
            />
          </FormField>
          <FormField label="E-mail" error={errors.email}>
            <input
              className="input"
              type="email"
              autoComplete="email"
              maxLength={255}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </FormField>
          <FormField
            label="Senha"
            error={errors.password}
            hint="Entre 8 e 72 caracteres."
          >
            <input
              className="input"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </FormField>
          <FormField label="Confirmar senha" error={errors.confirmPassword}>
            <input
              className="input"
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
            />
          </FormField>

          {serverError && (
            <div className="auth-error" role="alert">
              <AlertCircle size={16} aria-hidden="true" />
              <span>{serverError}</span>
            </div>
          )}

          <button type="submit" className="btn btn-primary" disabled={register.isPending}>
            {register.isPending ? 'Criando conta…' : 'Criar conta'}
          </button>
        </form>

        <p className="auth-switch">
          Já tem conta? <Link to="/login">Entrar</Link>
        </p>
      </div>
    </main>
  )
}
