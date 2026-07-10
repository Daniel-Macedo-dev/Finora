interface FormActionsProps {
  busy: boolean
  submitLabel: string
  busyLabel?: string
  onCancel: () => void
}

/** Standard dialog-form footer: cancel + submit with busy state. */
export default function FormActions({
  busy,
  submitLabel,
  busyLabel = 'Salvando…',
  onCancel,
}: FormActionsProps) {
  return (
    <div className="form-footer">
      <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={busy}>
        Cancelar
      </button>
      <button type="submit" className="btn btn-primary" disabled={busy}>
        {busy ? busyLabel : submitLabel}
      </button>
    </div>
  )
}
