import { useRef, useState } from 'react'
import Dialog from '../../../components/Dialog'
import FormActions from '../../../components/FormActions'
import { formatBRL } from '../../../lib/format'
import { useCaptureOptionPrice } from '../api'
import type { PurchaseOption } from '../types'
import { createRequestId } from './requestId'

interface Props {
  itemId: number
  option: PurchaseOption | null
  onClose: () => void
}

export default function CaptureOptionPriceDialog({ itemId, option, onClose }: Props) {
  const mutation = useCaptureOptionPrice(itemId, option?.id ?? 0)
  const requestId = useRef<string | null>(null)
  const [observedOn, setObservedOn] = useState(() => new Date().toISOString().slice(0, 10))
  const [offerUrl, setOfferUrl] = useState('')
  const [notes, setNotes] = useState('')

  return (
    <Dialog open={option !== null} title="Registrar preço atual" onClose={onClose} wide>
      {option && (
        <form onSubmit={(event) => {
          event.preventDefault()
          requestId.current ??= createRequestId()
          mutation.mutate({ clientRequestId: requestId.current, observedOn,
            offerUrl: offerUrl || null, notes: notes || null }, { onSuccess: onClose })
        }}>
          <div className="price-capture-values" aria-label="Valores atuais da opção">
            <p><strong>{option.merchant}</strong></p>
            <p>{option.kind === 'CASH' ? 'À vista' : `${option.installmentCount} parcelas`}</p>
            <p>Preço {formatBRL(option.basePrice)} · frete {formatBRL(option.shipping)} · taxas {formatBRL(option.fees)}</p>
            <p><strong>Total {formatBRL(option.nominalCost)}</strong></p>
          </div>
          <label className="form-field">Data da observação
            <input type="date" required value={observedOn} onChange={(e) => setObservedOn(e.target.value)} />
          </label>
          <label className="form-field">Link da oferta (opcional)
            <input type="url" placeholder="https://" value={offerUrl} onChange={(e) => setOfferUrl(e.target.value)} />
          </label>
          <label className="form-field">Observações (opcional)
            <textarea maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} />
          </label>
          {mutation.isError && <p className="form-error" role="alert">{mutation.error.message}</p>}
          <FormActions busy={mutation.isPending} submitLabel="Salvar no histórico" onCancel={onClose} />
        </form>
      )}
    </Dialog>
  )
}
