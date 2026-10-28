import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, TextareaHTMLAttributes } from 'react'

const control =
  'w-full rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-3 py-2 text-sm text-[var(--ink)] outline-none transition ' +
  'placeholder:text-[var(--muted)]/70 focus:border-[var(--accent)] focus:ring-4 focus:ring-[var(--accent-soft)]'

export function Field({ label, children, wide, hint }: { label: string; children: ReactNode; wide?: boolean; hint?: string }) {
  return (
    <label className={`flex flex-col gap-1.5 text-xs font-medium text-[var(--muted)] ${wide ? 'col-span-full' : ''}`}>
      {label}
      {children}
      {hint && <span className="font-normal text-[var(--muted)]/80">{hint}</span>}
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
    primary: 'bg-navy text-white shadow-sm hover:bg-navy-soft',
    ghost: 'border border-[var(--line)] bg-[var(--panel)] text-[var(--ink)] hover:bg-[var(--panel-2)] hover:border-[var(--muted)]/40',
    quiet: 'text-[var(--accent)] hover:bg-[var(--accent-soft)]',
    danger: 'border border-[var(--danger)]/40 text-[var(--danger)] hover:bg-[var(--danger-soft)]',
  }[variant]
  const sizes = size === 'lg' ? 'px-6 py-3 text-base' : 'px-4 py-1.5 text-sm'
  return (
    <button
      type="button"
      {...props}
      className={`rounded-full font-medium transition duration-150 active:scale-[0.98] disabled:pointer-events-none disabled:opacity-45 ${sizes} ${styles} ${props.className ?? ''}`}
    />
  )
}
