import { useEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import './Dialog.css'

interface DialogProps {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  /** Wider layout for forms with two columns. */
  wide?: boolean
}

/**
 * Modal dialog built on the native <dialog> element: focus trapping, Escape
 * handling and inert background come from the platform.
 */
export default function Dialog({ open, title, onClose, children, wide }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const opener = useRef<HTMLElement | null>(null)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) {
      return
    }
    if (open && !dialog.open) {
      opener.current = document.activeElement as HTMLElement | null
      dialog.showModal()
    } else if (!open && dialog.open) {
      dialog.close()
    }
  }, [open])

  // Closing unmounts the <dialog> before the platform can run its own focus
  // restoration, so without this the caret lands on <body> and a keyboard user
  // loses their place — right after being asked about something destructive,
  // which is the worst moment to be dropped at the top of the page.
  useEffect(() => {
    if (!open) {
      return
    }
    return () => {
      const target = opener.current
      opener.current = null
      if (target?.isConnected) {
        target.focus()
      }
    }
  }, [open])

  if (!open) {
    return null
  }

  return (
    <dialog
      ref={ref}
      className={`dialog ${wide ? 'dialog-wide' : ''}`}
      aria-label={title}
      onClose={onClose}
      onCancel={onClose}
    >
      <div className="dialog-header">
        <h2 className="dialog-title">{title}</h2>
        <button
          type="button"
          className="btn btn-ghost btn-icon"
          onClick={onClose}
          aria-label="Fechar"
        >
          <X size={18} aria-hidden="true" />
        </button>
      </div>
      <div className="dialog-body">{children}</div>
    </dialog>
  )
}
