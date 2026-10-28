import type { ReactNode } from 'react'

export type Tone = 'neutral' | 'accent' | 'success' | 'warn' | 'danger'

const TONES: Record<Tone, string> = {
  neutral: 'bg-[var(--hover)] text-[var(--muted)]',
  accent: 'bg-[var(--accent-soft)] text-[var(--accent)]',
  success: 'bg-[var(--success-soft)] text-[var(--success)]',
  warn: 'bg-[var(--warn-soft)] text-[var(--warn)]',
  danger: 'bg-[var(--danger-soft)] text-[var(--danger)]',
}

/** A small rounded label for a state ("Ready", "Needs you", "Submitted"). */
export function Pill({ tone = 'neutral', children, className = '' }: { tone?: Tone; children: ReactNode; className?: string }) {
  return <span className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium ${TONES[tone]} ${className}`}>{children}</span>
}
