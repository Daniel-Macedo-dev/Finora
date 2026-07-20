import { useMemo, useState } from 'react'
import { Upload } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { formatDate } from '../../lib/format'
import { useAccounts } from '../shared/api'
import { useImportHistory } from './api'
import ImportDetail from './ImportDetail'
import StatementUploadDialog from './StatementUploadDialog'
import { BATCH_STATUS_LABELS, type StatementImportStatus } from './types'
import './statement-imports.css'

const STATUS_BADGES: Record<StatementImportStatus, string> = {
  NEEDS_MAPPING: 'badge-warning',
  PREVIEW_READY: 'badge-info',
  COMPLETED: 'badge-positive',
  PARTIALLY_COMPLETED: 'badge-warning',
  UNDONE: 'badge-neutral',
}

export default function StatementImportsPage() {
  const [uploadOpen, setUploadOpen] = useState(false)
  const [accountFilter, setAccountFilter] = useState('')
  const [page, setPage] = useState(0)
  const [openBatchId, setOpenBatchId] = useState<number | null>(null)

  const filters = useMemo(
    () => ({
      accountId: accountFilter ? Number(accountFilter) : undefined,
      page,
    }),
    [accountFilter, page],
  )
  const history = useImportHistory(filters)
  const accounts = useAccounts()

  if (openBatchId !== null) {
    return <ImportDetail batchId={openBatchId} onBack={() => setOpenBatchId(null)} />
  }

  const data = history.data
  const batches = data?.content ?? []

  return (
    <>
      <PageHeader
        title="Importar extrato"
        description="Envie extratos CSV ou OFX do seu banco, revise cada lançamento e importe sem duplicar transações."
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setUploadOpen(true)}>
            <Upload size={16} aria-hidden="true" />
            Importar extrato
          </button>
        }
      />

      <div className="si-filters">
        <select
          className="select si-filter"
          aria-label="Filtrar por conta"
          value={accountFilter}
          onChange={(event) => {
            setAccountFilter(event.target.value)
            setPage(0)
          }}
        >
          <option value="">Todas as contas</option>
          {(accounts.data ?? [])
            .filter((account) => account.type === 'CHECKING' || account.type === 'SAVINGS')
            .map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
        </select>
      </div>

      {history.isPending ? (
        <LoadingCards count={3} height={90} />
      ) : history.isError ? (
        <ErrorState error={history.error} onRetry={() => history.refetch()} />
      ) : batches.length === 0 ? (
        <EmptyState
          title="Nenhuma importação ainda"
          description="Envie o extrato da sua conta para revisar e importar os lançamentos. O arquivo bruto não fica armazenado."
          action={
            <button type="button" className="btn btn-primary" onClick={() => setUploadOpen(true)}>
              <Upload size={16} aria-hidden="true" />
              Importar extrato
            </button>
          }
        />
      ) : (
        <>
          <div className="card table-wrap" style={{ padding: 0 }}>
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Enviado em</th>
                  <th scope="col">Arquivo</th>
                  <th scope="col" className="si-col-optional">
                    Conta
                  </th>
                  <th scope="col" className="si-col-optional">
                    Formato
                  </th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Importados
                  </th>
                  <th scope="col">Situação</th>
                  <th scope="col">
                    <span className="visually-hidden">Ações</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {batches.map((batch) => (
                  <tr key={batch.id}>
                    <td>{formatDate(batch.createdAt.slice(0, 10))}</td>
                    <td className="si-filename" title={batch.originalFilename}>
                      {batch.originalFilename}
                    </td>
                    <td className="si-col-optional">{batch.accountName}</td>
                    <td className="si-col-optional">{batch.format}</td>
                    <td style={{ textAlign: 'right' }}>
                      {batch.importedCount} de {batch.totalRows}
                      {batch.failedCount > 0 && (
                        <span className="si-failed-note"> · {batch.failedCount} com falha</span>
                      )}
                    </td>
                    <td>
                      <span className={`badge ${STATUS_BADGES[batch.status]}`}>
                        {BATCH_STATUS_LABELS[batch.status]}
                      </span>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <button
                        type="button"
                        className="btn btn-secondary"
                        onClick={() => setOpenBatchId(batch.id)}
                      >
                        Abrir
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {data && data.totalPages > 1 && (
            <nav className="si-pagination" aria-label="Paginação">
              <button
                type="button"
                className="btn btn-secondary"
                disabled={page === 0}
                onClick={() => setPage((value) => value - 1)}
              >
                Anterior
              </button>
              <span className="si-pagination-info">
                Página {data.page + 1} de {data.totalPages}
              </span>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={data.page + 1 >= data.totalPages}
                onClick={() => setPage((value) => value + 1)}
              >
                Próxima
              </button>
            </nav>
          )}
        </>
      )}

      <StatementUploadDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onUploaded={(batch) => {
          setUploadOpen(false)
          setOpenBatchId(batch.id)
        }}
      />
    </>
  )
}
