import { useState } from 'react'
import Dialog from '../../components/Dialog'
import FormField from '../../components/FormField'
import FormActions from '../../components/FormActions'
import { errorMessage } from '../../components/states'
import { useCreditCards } from '../credit-cards/api'
import CardSelect from '../credit-cards/CardSelect'
import { useMapLegacyCredit } from './api'
import type { Commitment } from './types'

interface LegacyCardMappingDialogProps {
  commitment: Commitment
  onClose: () => void
}

/**
 * Migrates a legacy CREDIT recurring definition to a real card target. The
 * backend sets the automation horizon to the mapping day, so this dialog's
 * central promise — no historical backfill — is enforced server-side; the
 * copy here only explains it.
 */
export default function LegacyCardMappingDialog({
  commitment,
  onClose,
}: LegacyCardMappingDialogProps) {
  const [cardId, setCardId] = useState('')
  const [installments, setInstallments] = useState('1')
  const [executionMode, setExecutionMode] = useState<'MANUAL' | 'AUTOMATIC'>('MANUAL')

  const cards = useCreditCards()
  const mapping = useMapLegacyCredit()

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    mapping.mutate(
      {
        id: commitment.id,
        request: {
          creditCardId: Number(cardId),
          installmentCount: Number(installments),
          executionMode,
        },
      },
      { onSuccess: onClose },
    )
  }

  return (
    <Dialog open title={`Migrar "${commitment.description}" para cartão`} onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <p className="commitment-form-note" style={{ marginBottom: 'var(--space-3)' }}>
          Este recorrente foi criado antes da área de Cartões e hoje é apenas planejamento.
          Escolha o cartão real que passará a receber as compras.
        </p>

        <FormField label="Cartão de destino">
          <CardSelect cards={cards.data ?? []} value={cardId} onChange={setCardId} required />
        </FormField>

        <FormField label="Número de parcelas por ocorrência">
          <input
            className="input"
            type="number"
            min="1"
            max="120"
            required
            value={installments}
            onChange={(event) => setInstallments(event.target.value)}
          />
        </FormField>

        <FormField
          label="Modo de execução"
          hint="No modo automático, ocorrências vencidas a partir de hoje são executadas no processamento."
        >
          <select
            className="select"
            value={executionMode}
            onChange={(event) => setExecutionMode(event.target.value as 'MANUAL' | 'AUTOMATIC')}
          >
            <option value="MANUAL">Manual — você executa cada ocorrência</option>
            <option value="AUTOMATIC">Automático — executa no vencimento</option>
          </select>
        </FormField>

        <p className="commitment-form-note" role="note" style={{ marginBottom: 'var(--space-3)' }}>
          Sem retroativos: as ocorrências históricas permanecem exatamente como estão e nada é
          criado para o passado. Somente ocorrências futuras, ainda não executadas, passam a
          gerar compras no cartão escolhido.
        </p>

        {mapping.isError && (
          <div role="alert" className="field-error">
            {errorMessage(mapping.error)}
          </div>
        )}

        <FormActions
          busy={mapping.isPending}
          submitLabel="Migrar para o cartão"
          busyLabel="Migrando…"
          onCancel={onClose}
        />
      </form>
    </Dialog>
  )
}
