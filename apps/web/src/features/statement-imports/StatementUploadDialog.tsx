import { useMemo, useRef, useState, type FormEvent } from 'react'
import Dialog from '../../components/Dialog'
import FormField from '../../components/FormField'
import { ApiError } from '../../lib/api'
import { errorMessage } from '../../components/states'
import { useAccounts } from '../shared/api'
import { useUploadStatement } from './api'
import { MAX_UPLOAD_BYTES, type BatchDetail } from './types'

interface StatementUploadDialogProps {
  open: boolean
  onClose: () => void
  onUploaded: (batch: BatchDetail) => void
}

const ACCEPTED_EXTENSIONS = ['.csv', '.ofx']

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} MB`
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

/**
 * Upload step: explicit destination account, file selection with local
 * size/extension validation, and the privacy explanation. The backend does
 * the authoritative parsing — this dialog never reads the file contents.
 */
export default function StatementUploadDialog({
  open,
  onClose,
  onUploaded,
}: StatementUploadDialogProps) {
  const accounts = useAccounts()
  const upload = useUploadStatement()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [accountId, setAccountId] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)

  // Statement import targets bank accounts only; card statements belong to
  // the Cartões area and are blocked by the backend.
  const eligibleAccounts = useMemo(
    () =>
      (accounts.data ?? []).filter(
        (account) => !account.archived && (account.type === 'CHECKING' || account.type === 'SAVINGS'),
      ),
    [accounts.data],
  )

  function handleFileChange(selected: File | null) {
    setLocalError(null)
    if (!selected) {
      setFile(null)
      return
    }
    const name = selected.name.toLowerCase()
    if (!ACCEPTED_EXTENSIONS.some((extension) => name.endsWith(extension))) {
      setFile(null)
      setLocalError('Envie um arquivo CSV ou OFX exportado pelo seu banco.')
      return
    }
    if (selected.size > MAX_UPLOAD_BYTES) {
      setFile(null)
      setLocalError(`O arquivo excede o limite de ${formatSize(MAX_UPLOAD_BYTES)}.`)
      return
    }
    setFile(selected)
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!accountId || !file) {
      setLocalError('Escolha a conta de destino e o arquivo do extrato.')
      return
    }
    upload.mutate(
      { accountId: Number(accountId), file },
      {
        onSuccess: (batch) => {
          setFile(null)
          setAccountId('')
          onUploaded(batch)
        },
      },
    )
  }

  const serverError = upload.isError ? errorMessage(upload.error) : null
  const serverCode = upload.error instanceof ApiError ? upload.error.code : undefined

  return (
    <Dialog open={open} title="Importar extrato bancário" onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        <FormField
          label="Conta de destino"
          hint="Somente contas correntes e poupanças recebem extratos."
        >
          <select
            className="select"
            value={accountId}
            onChange={(event) => setAccountId(event.target.value)}
            required
          >
            <option value="">Escolha a conta</option>
            {eligibleAccounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </select>
        </FormField>

        <FormField
          label="Arquivo do extrato"
          hint={`Formatos aceitos: CSV e OFX. Tamanho máximo: ${formatSize(MAX_UPLOAD_BYTES)}.`}
          error={localError ?? undefined}
        >
          <input
            ref={fileInputRef}
            className="input"
            type="file"
            accept=".csv,.ofx"
            onChange={(event) => handleFileChange(event.target.files?.[0] ?? null)}
          />
        </FormField>

        {file && (
          <p className="si-file-meta" aria-live="polite">
            Selecionado: <strong className="si-filename">{file.name}</strong> (
            {formatSize(file.size)})
          </p>
        )}

        <p className="si-privacy-note">
          O arquivo é lido apenas para gerar a pré-visualização e{' '}
          <strong>não fica armazenado</strong>: após o processamento, o conteúdo bruto é
          descartado e somente os lançamentos normalizados permanecem no histórico de
          importação.
        </p>

        {serverError && (
          <p className="field-error" role="alert">
            {serverError}
            {serverCode === 'STATEMENT_CARD_NOT_SUPPORTED' && ' Acesse a área Cartões.'}
          </p>
        )}

        <div className="form-footer">
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancelar
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={upload.isPending || !file || !accountId}
          >
            {upload.isPending ? 'Enviando…' : 'Enviar extrato'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
