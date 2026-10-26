import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, TextareaHTMLAttributes } from 'react'

const control =
  'w-full rounded-md border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1.5 text-sm outline-none focus:border-[var(--accent)]'

export function Field({ label, children, wide }: { label: string; children: ReactNode; wide?: boolean }) {
  return (
    <label className={`flex flex-col gap-1 text-xs font-medium text-[var(--muted)] ${wide ? 'col-span-full' : ''}`}>
      {label}
      {children}
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
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  const styles = {
    primary: 'bg-navy text-white hover:bg-navy-soft',
    ghost: 'border border-[var(--line)] text-[var(--ink)] hover:bg-[var(--surface)]',
    danger: 'border border-red-300 text-red-600 hover:bg-red-50 dark:hover:bg-red-950',
  }[variant]
  return (
    <button
      type="button"
      {...props}
      className={`rounded-md px-3 py-1.5 text-sm font-medium disabled:opacity-50 ${styles} ${props.className ?? ''}`}
    />
  )
}
