import { useState, type FormEvent } from 'react'
import PageHeader from '../../components/PageHeader'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { formatDate } from '../../lib/format'
import { ApiError } from '../../lib/api'
import { useChangePassword, useCurrentUser, useUpdateProfile } from '../auth/api'
import './profile.css'

export default function ProfilePage() {
  const currentUser = useCurrentUser()
  const updateProfile = useUpdateProfile()
  const changePassword = useChangePassword()

  const [displayName, setDisplayName] = useState<string | null>(null)
  const [nameSaved, setNameSaved] = useState(false)

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmNewPassword, setConfirmNewPassword] = useState('')
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [passwordSaved, setPasswordSaved] = useState(false)

  const user = currentUser.data
  if (!user) {
    return null // RequireAuth guarantees presence; hydration handled upstream.
  }

  const nameValue = displayName ?? user.displayName

  function handleNameSubmit(event: FormEvent) {
    event.preventDefault()
    setNameSaved(false)
    if (!nameValue.trim()) {
      return
    }
    updateProfile.mutate(
      { displayName: nameValue.trim() },
      { onSuccess: () => setNameSaved(true) },
    )
  }

  function handlePasswordSubmit(event: FormEvent) {
    event.preventDefault()
    setPasswordSaved(false)
    if (newPassword.length < 8 || newPassword.length > 72) {
      setPasswordError('A nova senha deve ter entre 8 e 72 caracteres.')
      return
    }
    if (confirmNewPassword !== newPassword) {
      setPasswordError('A confirmação não coincide com a nova senha.')
      return
    }
    setPasswordError(null)
    changePassword.mutate(
      { currentPassword, newPassword },
      {
        onSuccess: () => {
          setPasswordSaved(true)
          setCurrentPassword('')
          setNewPassword('')
          setConfirmNewPassword('')
        },
      },
    )
  }

  const passwordServerError = changePassword.error
    ? changePassword.error instanceof ApiError &&
      changePassword.error.code === 'CURRENT_PASSWORD_INVALID'
      ? 'Senha atual incorreta.'
      : errorMessage(changePassword.error)
    : null

  return (
    <>
      <PageHeader
        title="Perfil"
        description="Sua identidade no Finora e a segurança da conta."
      />

      <section className="card profile-section" aria-label="Dados da conta">
        <h2 className="panel-title">Dados da conta</h2>
        <dl className="profile-facts">
          <div>
            <dt>E-mail</dt>
            <dd>{user.email}</dd>
          </div>
          <div>
            <dt>Conta criada em</dt>
            <dd>{formatDate(user.createdAt.slice(0, 10))}</dd>
          </div>
        </dl>
        <form onSubmit={handleNameSubmit} noValidate className="form-grid">
          <FormField label="Nome de exibição">
            <input
              className="input"
              maxLength={100}
              value={nameValue}
              onChange={(event) => setDisplayName(event.target.value)}
            />
          </FormField>
          {updateProfile.error != null && (
            <div role="alert" className="field-error">
              {errorMessage(updateProfile.error)}
            </div>
          )}
          {nameSaved && !updateProfile.error && (
            <p role="status" className="profile-saved">
              Nome atualizado.
            </p>
          )}
          <div>
            <button type="submit" className="btn btn-primary" disabled={updateProfile.isPending}>
              {updateProfile.isPending ? 'Salvando…' : 'Salvar nome'}
            </button>
          </div>
        </form>
      </section>

      <section className="card profile-section" aria-label="Alterar senha">
        <h2 className="panel-title">Alterar senha</h2>
        <p className="profile-note">
          Ao alterar a senha, suas outras sessões ativas são encerradas — apenas esta
          permanece conectada.
        </p>
        <form onSubmit={handlePasswordSubmit} noValidate className="form-grid">
          <FormField label="Senha atual">
            <input
              className="input"
              type="password"
              autoComplete="current-password"
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
            />
          </FormField>
          <FormField label="Nova senha" hint="Entre 8 e 72 caracteres.">
            <input
              className="input"
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
            />
          </FormField>
          <FormField label="Confirmar nova senha">
            <input
              className="input"
              type="password"
              autoComplete="new-password"
              value={confirmNewPassword}
              onChange={(event) => setConfirmNewPassword(event.target.value)}
            />
          </FormField>
          {(passwordError || passwordServerError) && (
            <div role="alert" className="field-error">
              {passwordError ?? passwordServerError}
            </div>
          )}
          {passwordSaved && !passwordError && !changePassword.error && (
            <p role="status" className="profile-saved">
              Senha alterada com sucesso.
            </p>
          )}
          <div>
            <button type="submit" className="btn btn-primary" disabled={changePassword.isPending}>
              {changePassword.isPending ? 'Alterando…' : 'Alterar senha'}
            </button>
          </div>
        </form>
      </section>
    </>
  )
}
