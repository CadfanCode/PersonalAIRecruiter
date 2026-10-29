import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { Button } from './Form'

/** A small modal that asks before something that cannot be undone. Escape and a click outside cancel it. */
export function ConfirmDialog({ title, children, confirmLabel, busy, error, onConfirm, onCancel }: {
  title: string
  children: ReactNode
  confirmLabel: string
  busy?: boolean
  error?: string
  onConfirm: () => void
  onCancel: () => void
}) {
  const cancel = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const back = document.activeElement as HTMLElement | null
    cancel.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onCancel() }
    window.addEventListener('keydown', onKey)
    return () => { window.removeEventListener('keydown', onKey); back?.focus?.() }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4 backdrop-blur-[2px]" onMouseDown={(e) => { if (e.target === e.currentTarget) onCancel() }}>
      <div role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" className="animate-rise w-full max-w-md rounded-xl border border-[var(--line)] bg-[var(--panel)] p-7 shadow-[var(--shadow-lift)]">
        <h2 id="confirm-title" className="text-xl font-semibold tracking-tight">{title}</h2>
        <div className="mt-2 text-[15px] leading-relaxed text-[var(--muted)]">{children}</div>
        {error && <p role="alert" className="mt-3 text-sm text-[var(--danger)]">{error}</p>}
        <div className="mt-6 flex justify-end gap-2">
          <button ref={cancel} type="button" onClick={onCancel}
            className="rounded-md border border-[var(--line-strong)] bg-[var(--panel)] px-3.5 py-1.5 text-sm text-[var(--ink)] transition-colors hover:bg-[var(--hover)]">Cancel</button>
          <Button variant="danger" onClick={onConfirm} disabled={busy}>{busy ? 'Deleting…' : confirmLabel}</Button>
        </div>
      </div>
    </div>
  )
}
