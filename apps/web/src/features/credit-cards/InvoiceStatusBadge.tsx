import { INVOICE_STATUS_LABELS, type InvoiceStatus } from './types'

const STATUS_CLASS: Record<InvoiceStatus, string> = {
  UPCOMING: 'badge-neutral',
  OPEN: 'badge-info',
  CLOSED: 'badge-warning',
  PARTIALLY_PAID: 'badge-warning',
  OVERDUE: 'badge-negative',
  PAID: 'badge-positive',
}

/** Invoice status chip: label + color, never color alone. */
export default function InvoiceStatusBadge({ status }: { status: InvoiceStatus }) {
  return <span className={`badge ${STATUS_CLASS[status]}`}>{INVOICE_STATUS_LABELS[status]}</span>
}
