import { useEffect, useRef, useState } from 'react'
import { type ImportedEntry, type ImportPreview, useImportCommit, useImportPreview } from '../../api/cv'
import { Button } from '../../components/Form'
import { CheckIcon } from '../../components/ui/Icons'
import { Processing } from '../../components/ui/Processing'
import { formatRange } from '../../components/cv/dates'

const KIND_LABEL: Record<string, string> = {
  WORK: 'Work', INTERNSHIP: 'Internships and placements', OWN_BUSINESS: 'Own business', PROJECT: 'Projects', EDUCATION: 'Education',
  CERTIFICATION: 'Certifications', LICENCE: 'Licences', COURSE: 'Courses', AWARD: 'Awards', CONFERENCE: 'Conferences',
  PUBLICATION: 'Publications', VOLUNTEER: 'Volunteering', LANGUAGE: 'Languages', OTHER: 'Other',
}

/** One extracted entry with its dates and the facts that were found under it. */
function EntryDetails({ entry }: { entry: ImportedEntry }) {
  const range = formatRange(entry)
  const facts = entry.facts.map((f) => f.textEn ?? f.textSv).filter((t): t is string => !!t)
  return (
    <li className="rounded-xl bg-[var(--bg)] px-3 py-2 text-sm">
      <div className="font-medium">{entry.title}</div>
      <div className="text-[var(--muted)]">{[entry.organisation, entry.location, range].filter(Boolean).join(', ')}</div>
      {facts.length > 0 && (
        <ul className="mt-1 list-disc pl-5 text-[13px] leading-relaxed">
          {facts.map((t, i) => <li key={i}>{t}</li>)}
        </ul>
      )}
    </li>
  )
}

/** The reading takes a minute and reports no progress, so the ring eases towards 90% instead of pretending to know. */
function useEasedProgress(running: boolean) {
  const [pct, setPct] = useState(4)
  useEffect(() => {
    if (!running) return
    const t = window.setInterval(() => setPct((p) => p + (90 - p) * 0.045), 800)
    return () => window.clearInterval(t)
  }, [running])
  return pct
}

/**
 * Between Claude and the country: the held files are read now that Claude is connected, a short summary is shown, and the user
 * confirms. Anything can be corrected later in the CV database.
 */
export function ImportStep({ files, onDone, onBack }: { files: File[]; onDone: () => void; onBack: () => void }) {
  const analyse = useImportPreview()
  const commit = useImportCommit()
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const started = useRef(false)
  const pct = useEasedProgress(analyse.isPending)
  const [open, setOpen] = useState<Set<string>>(new Set())
  const toggle = (kind: string) => setOpen((o) => { const n = new Set(o); if (n.has(kind)) n.delete(kind); else n.add(kind); return n })

  useEffect(() => {
    if (started.current || files.length === 0) return
    started.current = true
    analyse.mutate(files, { onSuccess: setPreview })
  }, [files, analyse])

  if (analyse.isError) {
    return (
      <div>
        <h1 className="text-3xl font-semibold tracking-tight">We could not read your CV</h1>
        <p role="alert" className="mt-3 rounded-2xl bg-[var(--danger-soft)] px-4 py-3 text-sm text-[var(--danger)]">{analyse.error.message}</p>
        <div className="mt-8 flex justify-between">
          <Button variant="ghost" onClick={onBack}>Choose other files</Button>
          <Button size="lg" onClick={() => { started.current = false; analyse.reset(); analyse.mutate(files, { onSuccess: setPreview }); started.current = true }}>Try again</Button>
        </div>
      </div>
    )
  }

  if (!preview) {
    return <Processing title="Reading your CV" percent={pct} etaSeconds={null} fallback="This usually takes a minute or two." message="Splitting it into experience, education and skills." />
  }

  const fresh = preview.entries.filter((e) => e.duplicateOfEntryId === null)
  const byKind = new Map<string, number>()
  fresh.forEach(({ entry }) => byKind.set(entry.kind, (byKind.get(entry.kind) ?? 0) + 1))
  const kinds = Object.keys(KIND_LABEL).filter((k) => k !== 'OTHER' || byKind.has(k)).map((k) => [k, byKind.get(k) ?? 0] as const)
  const facts = fresh.reduce((n, e) => n + e.entry.facts.length, 0)
  const who = preview.profile?.fullName

  const confirm = () =>
    commit.mutate(
      { previewId: preview.previewId, entries: fresh.map((e) => e.entry), skills: preview.skills, profile: preview.profile },
      { onSuccess: onDone },
    )

  return (
    <div>
      <h1 className="text-3xl font-semibold tracking-tight">{who ? `Here is what we found, ${who.split(' ')[0]}` : 'Here is what we found'}</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">
        {fresh.length} {fresh.length === 1 ? 'entry' : 'entries'} with {facts} facts and {preview.skills.length} skills. You can correct anything
        later in the CV database.
      </p>

      <ul aria-label="What was found" className="mt-6 grid items-start gap-2 sm:grid-cols-2">
        {kinds.map(([kind, n]) => (
          <li key={kind} className="rounded-2xl border border-[var(--line)] bg-[var(--panel)] text-sm">
            {n > 0 ? (
              <button
                type="button"
                aria-expanded={open.has(kind)}
                aria-controls={`found-${kind}`}
                onClick={() => toggle(kind)}
                className="flex w-full items-center justify-between rounded-2xl px-4 py-3 text-left hover:bg-[var(--accent-soft)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent)]"
              >
                <span className="font-medium">{KIND_LABEL[kind] ?? kind}</span>
                <span className="rounded-full bg-[var(--accent-soft)] px-2.5 py-0.5 text-xs font-semibold text-[var(--accent)]">{n}</span>
              </button>
            ) : (
              <div className="flex items-center justify-between px-4 py-3 text-[var(--muted)] opacity-60">
                <span className="font-medium">{KIND_LABEL[kind] ?? kind}</span>
                <span className="rounded-full px-2.5 py-0.5 text-xs font-semibold">0</span>
              </div>
            )}
            {n > 0 && open.has(kind) && (
              <ul id={`found-${kind}`} className="flex flex-col gap-2 px-3 pb-3">
                {fresh.filter((e) => e.entry.kind === kind).map((e, i) => <EntryDetails key={i} entry={e.entry} />)}
              </ul>
            )}
          </li>
        ))}
      </ul>

      <div className="mt-5 rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-4">
        <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-[var(--muted)]">Most recent</h2>
        <ul className="flex flex-col gap-1.5 text-sm">
          {fresh.slice(0, 4).map(({ entry }, i) => (
            <li key={i} className="flex flex-wrap gap-x-2">
              <span className="font-medium">{entry.title}</span>
              <span className="text-[var(--muted)]">{[entry.organisation, entry.startYm && `${entry.startYm} to ${entry.ongoing ? 'now' : (entry.endYm ?? '?')}`].filter(Boolean).join(', ')}</span>
            </li>
          ))}
        </ul>
      </div>

      {preview.entries.length > fresh.length && (
        <p className="mt-3 text-sm text-[var(--muted)]">{preview.entries.length - fresh.length} entries were already in your database and are left as they are.</p>
      )}
      {commit.isError && <p role="alert" className="mt-4 text-sm text-[var(--danger)]">{commit.error.message}</p>}

      <div className="mt-8 flex justify-end">
        <Button size="lg" onClick={confirm} disabled={commit.isPending}>
          {commit.isPending ? 'Saving…' : <span className="flex items-center gap-2"><CheckIcon width={16} height={16} /> Looks right, continue</span>}
        </Button>
      </div>
    </div>
  )
}
