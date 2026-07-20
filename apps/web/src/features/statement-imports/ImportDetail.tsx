import { ArrowLeft, FileText } from 'lucide-react'
import { ErrorState, LoadingCards } from '../../components/states'
import { formatDate } from '../../lib/format'
import { useImportBatch } from './api'
import CsvMappingStep from './CsvMappingStep'
import ImportPreview from './ImportPreview'
import { BATCH_STATUS_LABELS, type BatchDetail, type StatementImportStatus } from './types'

interface ImportDetailProps {
  batchId: number
  onBack: () => void
}

const STATUS_BADGES: Record<StatementImportStatus, string> = {
  NEEDS_MAPPING: 'badge-warning',
  PREVIEW_READY: 'badge-info',
  COMPLETED: 'badge-positive',
  PARTIALLY_COMPLETED: 'badge-warning',
  UNDONE: 'badge-neutral',
}

function BatchHeader({ batch }: { batch: BatchDetail }) {
  return (
    <div className="si-batch-header card">
      <div className="si-batch-title">
        <FileText size={18} aria-hidden="true" />
        <span className="si-filename">{batch.originalFilename}</span>
        <span className={`badge ${STATUS_BADGES[batch.status]}`}>
          {BATCH_STATUS_LABELS[batch.status]}
        </span>
      </div>
      <dl className="si-batch-meta">
        <div>
          <dt>Conta de destino</dt>
          <dd>{batch.accountName}</dd>
        </div>
        <div>
          <dt>Formato</dt>
          <dd>{batch.format}</dd>
        </div>
        <div>
          <dt>Enviado em</dt>
          <dd>{formatDate(batch.createdAt.slice(0, 10))}</dd>
        </div>
        {batch.sourceAccountHint && (
          <div>
            <dt>Conta no arquivo</dt>
            <dd>{batch.sourceAccountHint}</dd>
          </div>
        )}
      </dl>
      {batch.fileAlreadyImported && (
        <p className="si-warning" role="status">
          Este arquivo já foi enviado para esta conta antes. Lançamentos repetidos aparecem
          como duplicatas e não serão importados de novo sem uma decisão explícita.
        </p>
      )}
    </div>
  )
}

/** Workbench of one import batch: mapping, preview, confirmation and undo. */
export default function ImportDetail({ batchId, onBack }: ImportDetailProps) {
  const batch = useImportBatch(batchId)

  return (
    <section aria-label="Detalhe da importação de extrato">
      <button type="button" className="btn btn-ghost si-back" onClick={onBack}>
        <ArrowLeft size={16} aria-hidden="true" />
        Voltar ao histórico
      </button>

      {batch.isPending ? (
        <LoadingCards count={3} height={100} />
      ) : batch.isError ? (
        <ErrorState error={batch.error} onRetry={() => batch.refetch()} />
      ) : batch.data ? (
        <>
          <BatchHeader batch={batch.data} />
          {batch.data.status === 'NEEDS_MAPPING' ? (
            <CsvMappingStep batch={batch.data} />
          ) : (
            <ImportPreview batch={batch.data} />
          )}
        </>
      ) : null}
    </section>
  )
}
