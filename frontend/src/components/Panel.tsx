import type { ReactNode } from 'react'

export function PageTitle({ children, subtitle }: { children: ReactNode; subtitle?: ReactNode }) {
  return (
    <header className="mb-6">
      <h1 className="text-[1.75rem] font-semibold leading-tight">{children}</h1>
      {subtitle && <p className="mt-1.5 max-w-2xl text-[15px] leading-relaxed text-[var(--muted)]">{subtitle}</p>}
    </header>
  )
}

export function Panel({ title, children, className = '' }: { title?: string; children: ReactNode; className?: string }) {
  return (
    <section className={`panel-edge rounded-xl border border-[var(--line)] bg-[var(--panel)] p-6 ${className}`}>
      {title && <h2 className="mb-4 text-[13px] font-semibold text-[var(--ink)]">{title}</h2>}
      {children}
    </section>
  )
}
