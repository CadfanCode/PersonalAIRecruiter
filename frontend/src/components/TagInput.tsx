import { useId, useState } from 'react'

/** Chips plus a text box. Enter or comma adds a tag; suggestions come from the existing tags. */
export function TagInput({
  label,
  value,
  onChange,
  suggestions = [],
}: {
  label: string
  value: string[]
  onChange: (tags: string[]) => void
  suggestions?: string[]
}) {
  const [draft, setDraft] = useState('')
  const listId = useId()

  const add = () => {
    const tag = draft.trim().toLowerCase()
    if (tag && !value.includes(tag)) onChange([...value, tag])
    setDraft('')
  }

  return (
    <div className="col-span-full flex flex-col gap-1 text-xs font-medium text-[var(--muted)]">
      <span>{label}</span>
      <div className="flex flex-wrap items-center gap-1.5 rounded-md border border-[var(--line)] bg-[var(--surface)] p-1.5">
        {value.map((tag) => (
          <span key={tag} className="flex items-center gap-1 rounded-full bg-navy/10 px-2 py-0.5 text-xs text-[var(--accent)]">
            {tag}
            <button type="button" aria-label={`Remove tag ${tag}`} onClick={() => onChange(value.filter((t) => t !== tag))}>
              ×
            </button>
          </span>
        ))}
        <input
          aria-label={`Add to ${label}`}
          list={listId}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ',') {
              e.preventDefault()
              add()
            }
          }}
          onBlur={add}
          placeholder="add tag"
          className="min-w-24 flex-1 bg-transparent px-1 py-0.5 text-sm text-[var(--ink)] outline-none"
        />
        <datalist id={listId}>
          {suggestions.filter((s) => !value.includes(s)).map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
      </div>
    </div>
  )
}
