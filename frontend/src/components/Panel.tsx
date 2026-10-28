import type { ReactNode } from 'react'

export function PageTitle({ children, subtitle }: { children: ReactNode; subtitle?: ReactNode }) {
  return (
    <header className="mb-6">
      <h1 className="text-3xl font-semibold tracking-tight">{children}</h1>
      {subtitle && <p className="mt-1.5 max-w-2xl text-[15px] leading-relaxed text-[var(--muted)]">{subtitle}</p>}
    </header>
  )
}

export function Panel({ title, children, className = '' }: { title?: string; children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-6 shadow-[var(--shadow)] ${className}`}>
      {title && <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-[var(--muted)]">{title}</h2>}
      {children}
    </section>
  )
}
