import { useState } from 'react'
import { type Entry, KINDS } from '../../api/cv'
import { Button } from '../Form'
import { Panel } from '../Panel'
import { attentionReasons, byRecency, formatRange } from './dates'
import { EntryEditor } from './EntryEditor'

const KIND_LABEL: Record<string, string> = {
  WORK: 'Work',
  INTERNSHIP: 'Internships',
  OWN_BUSINESS: 'Own business',
  PROJECT: 'Projects',
  EDUCATION: 'Education',
  CERTIFICATION: 'Certifications',
  LICENCE: 'Licences',
  COURSE: 'Courses',
  AWARD: 'Awards',
  CONFERENCE: 'Conferences',
  PUBLICATION: 'Publications',
  VOLUNTEER: 'Volunteering',
  LANGUAGE: 'Languages',
  OTHER: 'Other',
}

export function EntryList({ entries, tagSuggestions }: { entries: Entry[]; tagSuggestions: string[] }) {
  const [openId, setOpenId] = useState<number | 'new' | null>(null)
  const needAttention = entries.filter((e) => attentionReasons(e).length > 0).length

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center gap-3">
        <Button onClick={() => setOpenId('new')}>Add entry</Button>
        {needAttention > 0 && (
          <span className="text-sm text-[var(--warn)]">
            {needAttention} {needAttention === 1 ? 'entry needs' : 'entries need'} your attention
          </span>
        )}
      </div>

      {openId === 'new' && (
        <Panel title="New entry">
          <EntryEditor entry={null} tagSuggestions={tagSuggestions} onDone={() => setOpenId(null)} />
        </Panel>
      )}

      {KINDS.map((kind) => {
        const group = entries.filter((e) => e.kind === kind).sort(byRecency)
        if (group.length === 0) return null
        return (
          <section key={kind} aria-label={KIND_LABEL[kind]}>
            <h2 className="mb-2 text-sm font-semibold text-[var(--muted)]">{KIND_LABEL[kind]}</h2>
            <ul className="flex flex-col gap-2">
              {group.map((e) => (
                <li key={e.id} className="rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-4">
                  <button
                    type="button"
                    aria-expanded={openId === e.id}
                    onClick={() => setOpenId(openId === e.id ? null : e.id)}
                    className="flex w-full items-start justify-between gap-4 text-left"
                  >
                    <span>
                      <span className="block font-medium">{e.title}</span>
                      <span className="block text-sm text-[var(--muted)]">
                        {[e.organisation, e.location].filter(Boolean).join(', ')}
                      </span>
                      <span className="mt-1 flex flex-wrap gap-1">
                        {e.tags.map((t) => (
                          <span key={t} className="rounded-full bg-navy/10 px-2 py-0.5 text-xs text-[var(--accent)]">
                            {t}
                          </span>
                        ))}
                      </span>
                    </span>
                    <span className="shrink-0 text-right text-sm text-[var(--muted)]">
                      <span className="block">{formatRange(e)}</span>
                      <span className="block">{e.facts.length} facts</span>
                      {attentionReasons(e).map((r) => (
                        <span key={r} className="mt-1 block rounded bg-[var(--warn-soft)] px-1.5 py-0.5 text-xs text-[var(--warn)]">
                          {r}
                        </span>
                      ))}
                    </span>
                  </button>
                  {openId === e.id && <EntryEditor entry={e} tagSuggestions={tagSuggestions} onDone={() => setOpenId(null)} />}
                </li>
              ))}
            </ul>
          </section>
        )
      })}
    </div>
  )
}
