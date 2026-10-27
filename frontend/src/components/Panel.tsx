import type { ReactNode } from 'react'

export function PageTitle({ children }: { children: ReactNode }) {
  return <h1 className="mb-5 text-2xl font-semibold tracking-tight">{children}</h1>
}

export function Panel({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-[var(--line)] bg-[var(--panel)] p-5">
      {title && <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-[var(--muted)]">{title}</h2>}
      {children}
    </section>
  )
}
