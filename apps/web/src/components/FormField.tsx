import { useId, type ReactElement, cloneElement } from 'react'

interface FormFieldProps {
  label: string
  error?: string
  hint?: string
  children: ReactElement<Record<string, unknown>>
}

/**
 * Wires label, hint and validation message to the wrapped control with the
 * correct id / aria-describedby / aria-invalid associations.
 */
export default function FormField({ label, error, hint, children }: FormFieldProps) {
  const id = useId()
  const errorId = `${id}-error`
  const hintId = `${id}-hint`
  const describedBy =
    [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined

  const control = cloneElement(children, {
    id,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': describedBy,
  })

  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {control}
      {hint && !error && (
        <span id={hintId} className="field-hint">
          {hint}
        </span>
      )}
      {error && (
        <span id={errorId} className="field-error" role="alert">
          {error}
        </span>
      )}
    </div>
  )
}
