import { useEffect, useState } from 'react'
import Dialog from '../components/Dialog'
import type { DestructiveRisk } from './destructiveRisk'
import type { VaultCounts } from './VaultProvider'
import './vault-deletion.css'

export type DeletionIntent = 'LOGOUT' | 'DISABLE_OFFLINE'

interface VaultDeletionDialogProps {
  open: boolean
  risk: DestructiveRisk
  counts: VaultCounts
  intent: DeletionIntent
  /** True while the confirmed deletion is running. */
  busy?: boolean
  /** Set when the local record could not actually be removed. */
  failure?: string | null
  onCancel(): void
  /** Opens the synchronization centre so the copy can be unlocked and read. */
  onReview(): void
  /** Only ever reached from the second, explicit confirmation. */
  onConfirm(): void
}

/**
 * The one place that asks before the local encrypted copy is deleted.
 *
 * Every path that can destroy the vault — signing out, turning offline access
 * off, discarding an unreadable copy from the unlock screen — comes through
 * here, so the wording, the number of confirmations and the escape routes
 * cannot drift apart between them. It is also why the risk is a parameter
 * rather than something each caller re-derives: three components independently
 * deciding what "pending" means is how one of them ends up deciding wrong.
 *
 * Two stages, always. The first explains what is at stake and can never delete
 * anything; only the second, which states plainly that the action cannot be
 * undone, is wired to the deletion. The exception is a copy known to be empty,
 * where there is nothing to lose and a single confirmation is the honest amount
 * of friction.
 */
export default function VaultDeletionDialog({
  open,
  risk,
  counts,
  intent,
  busy = false,
  failure = null,
  onCancel,
  onReview,
  onConfirm,
}: VaultDeletionDialogProps) {
  const [final, setFinal] = useState(false)

  useEffect(() => {
    if (!open) setFinal(false)
  }, [open])

  if (!open || risk === 'NO_LOCAL_COPY' || risk === 'BUSY') return null

  const logout = intent === 'LOGOUT'
  const unknown = risk === 'UNKNOWN_LOCKED' || risk === 'UNKNOWN_CORRUPTED'
  // A copy whose queue is readable and empty has nothing to lose, so it gets
  // the ordinary "this deletes the local copy" confirmation and no second step.
  const singleStep = risk === 'KNOWN_SAFE'

  /** The two sentences every variant ends on, and the one it may have to add. */
  const closing = (
    <>
      <p className="vault-risk-text">Os dados já enviados ao servidor não são apagados.</p>
      {failure && (
        <p role="alert" className="field-error">
          {failure}
        </p>
      )}
    </>
  )

  if (final || singleStep) {
    return (
      <Dialog
        open
        title={logout ? 'Excluir a cópia offline e sair' : 'Excluir a cópia offline deste dispositivo'}
        onClose={() => (singleStep ? onCancel() : setFinal(false))}
      >
        <p className="vault-risk-text">{finalConsequence(risk, counts)}</p>
        {!singleStep && (
          <p className="vault-risk-text vault-risk-final">Essa ação não pode ser desfeita.</p>
        )}
        {closing}
        <div className="form-footer vault-risk-footer">
          <button
            type="button"
            className="btn btn-secondary"
            autoFocus
            disabled={busy}
            onClick={() => (singleStep ? onCancel() : setFinal(false))}
          >
            {singleStep ? 'Cancelar' : 'Voltar'}
          </button>
          <button type="button" className="btn btn-danger" disabled={busy} onClick={onConfirm}>
            {busy ? 'Excluindo…' : logout ? 'Excluir e sair definitivamente' : 'Excluir cópia definitivamente'}
          </button>
        </div>
      </Dialog>
    )
  }

  return (
    <Dialog open title={warningTitle(risk, intent)} onClose={onCancel}>
      {warningBody(risk, counts).map((paragraph) => (
        <p key={paragraph} className="vault-risk-text">
          {paragraph}
        </p>
      ))}
      {closing}
      <div className="form-footer vault-risk-footer">
        {/* Focus starts here on purpose: the destructive button must never be
            one stray Enter away from being pressed. */}
        <button
          type="button"
          className="btn btn-secondary"
          autoFocus
          disabled={busy}
          onClick={onCancel}
        >
          Cancelar
        </button>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={onReview}>
          {unknown ? 'Desbloquear e verificar' : 'Revisar alterações pendentes'}
        </button>
        <button
          type="button"
          className="btn btn-danger"
          disabled={busy}
          onClick={() => setFinal(true)}
        >
          {logout
            ? unknown
              ? 'Descartar cópia e sair'
              : 'Descartar alterações e sair'
            : 'Excluir cópia mesmo assim'}
        </button>
      </div>
    </Dialog>
  )
}

function warningTitle(risk: DestructiveRisk, intent: DeletionIntent): string {
  if (risk === 'UNKNOWN_LOCKED') return 'A cópia offline está bloqueada'
  if (risk === 'UNKNOWN_CORRUPTED') return 'A cópia offline não pôde ser verificada'
  return intent === 'LOGOUT'
    ? 'Sair com alterações offline pendentes'
    : 'Há alterações offline pendentes'
}

/**
 * What the application is able to say, and nothing more.
 *
 * The two unknown states describe a possibility ("pode conter") because that is
 * the whole truth available while the record is unreadable. Stating that pending
 * work exists would be a guess dressed as a fact, and a user who believed it and
 * then found an empty queue would have less reason to believe the next warning.
 */
function warningBody(risk: DestructiveRisk, counts: VaultCounts): string[] {
  if (risk === 'UNKNOWN_LOCKED') {
    return [
      'A cópia criptografada deste dispositivo pode conter alterações que ainda não foram '
      + 'enviadas ao servidor. Como ela está bloqueada, o Finora não pode verificar isso agora.',
      'Excluir a cópia pode apagar permanentemente essas alterações.',
    ]
  }
  if (risk === 'UNKNOWN_CORRUPTED') {
    return [
      'A cópia criptografada deste dispositivo não pôde ser lida nem validada, então o Finora '
      + 'não consegue verificar se ela contém alterações que ainda não foram enviadas ao servidor.',
      'Excluir a cópia pode apagar permanentemente alterações que nunca chegaram ao servidor.',
    ]
  }
  const detail = [
    counts.conflicts > 0 ? `${counts.conflicts} em conflito` : null,
    counts.permanent > 0 ? `${counts.permanent} com falha` : null,
  ].filter(Boolean)
  return [
    `Há ${counts.total} alteração(ões) offline que ainda não chegaram ao servidor`
    + (detail.length > 0 ? `, sendo ${detail.join(' e ')}` : '')
    + '. Excluir a cópia local criptografada deste dispositivo apaga essas alterações, e elas '
    + 'não existem em nenhum outro lugar.',
  ]
}

function finalConsequence(risk: DestructiveRisk, counts: VaultCounts): string {
  if (risk === 'KNOWN_PENDING') {
    return `As ${counts.total} alteração(ões) que ainda não chegaram ao servidor serão apagadas `
      + 'deste dispositivo.'
  }
  if (risk === 'KNOWN_SAFE') {
    return 'A cópia local criptografada deste dispositivo será excluída. Não há alterações '
      + 'pendentes registradas nela.'
  }
  return 'Se a cópia contiver alterações que nunca chegaram ao servidor, elas serão apagadas '
    + 'deste dispositivo.'
}
