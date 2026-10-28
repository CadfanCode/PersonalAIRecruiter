import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, TextareaHTMLAttributes } from 'react'

const control =
  'w-full rounded-md border border-[var(--line-strong)] bg-[var(--panel)] px-3 py-2 text-sm text-[var(--ink)] outline-none transition-colors ' +
  'placeholder:text-[var(--muted)]/70 hover:border-[var(--muted)]/60 focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent-soft)]'

export function Field({ label, children, wide, hint }: { label: string; children: ReactNode; wide?: boolean; hint?: string }) {
  return (
    <label className={`flex flex-col gap-1.5 text-[13px] font-medium text-[var(--ink)] ${wide ? 'col-span-full' : ''}`}>
      {label}
      {children}
      {hint && <span className="text-xs font-normal text-[var(--muted)]">{hint}</span>}
    </label>
  )
}

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={`${control} ${props.className ?? ''}`} />
}

export function TextArea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea rows={2} {...props} className={`${control} ${props.className ?? ''}`} />
}

export function Button({
  variant = 'primary',
  size = 'md',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' | 'quiet'; size?: 'md' | 'lg' }) {
  const styles = {
    primary: 'bg-[var(--accent)] text-[var(--on-accent)] hover:bg-[var(--accent-strong)]',
    ghost: 'border border-[var(--line-strong)] bg-[var(--panel)] text-[var(--ink)] hover:bg-[var(--hover)]',
    quiet: 'text-[var(--accent)] hover:bg-[var(--accent-soft)]',
    danger: 'border border-[var(--danger)]/40 text-[var(--danger)] hover:bg-[var(--danger-soft)]',
  }[variant]
  const sizes = size === 'lg' ? 'px-5 py-2.5 text-[15px]' : 'px-3.5 py-1.5 text-sm'
  return (
    <button
      type="button"
      {...props}
      className={`rounded-md font-medium transition-colors duration-150 disabled:opacity-45 ${sizes} ${styles} ${props.className ?? ''}`}
    />
  )
}
