import type { FactView } from '../../api/documents'

/** The CV facts a bullet or letter rests on. Hover (or focus) a chip to read the source fact. */
export function FactChips({ ids, facts, language }: { ids: number[]; facts: Record<string, FactView>; language: 'sv' | 'en' }) {
  return (
    <span className="inline-flex flex-wrap gap-1">
      {ids.map((id) => {
        const f = facts[String(id)]
        const text = f ? (language === 'sv' ? (f.textSv ?? f.textEn) : (f.textEn ?? f.textSv)) : null
        return (
          <span
            key={id}
            tabIndex={0}
            title={f ? `${f.entryTitle}: ${text}` : `Fact ${id} was not found`}
            aria-label={f ? `Source fact ${id}: ${text}` : `Unknown fact ${id}`}
            className={`cursor-help rounded border px-1.5 py-0.5 text-xs ${f ? 'border-[var(--line)] text-[var(--muted)]' : 'border-red-400 text-red-600'}`}
          >
            #{id}
          </span>
        )
      })}
    </span>
  )
}
