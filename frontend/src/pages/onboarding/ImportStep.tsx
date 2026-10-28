import { useEffect, useRef, useState } from 'react'
import { type ImportedEntry, useDiscardImport, useImportCommit, useImportJob, useStartImport } from '../../api/cv'
import { Button } from '../../components/Form'
import { CheckIcon } from '../../components/ui/Icons'
import { Processing } from '../../components/ui/Processing'
import { formatRange } from '../../components/cv/dates'
import { useEasedProgress } from '../../lib/useEasedProgress'
import { useSetup } from '../../setup/SetupProvider'

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

/**
 * Between Claude and the country: the held files are read now that Claude is connected, a short summary is shown, and the user
 * confirms. Anything can be corrected later in the CV database.
 */
export function ImportStep({ onDone, onBack }: { onDone: () => void; onBack: () => void }) {
  const { files, setFiles } = useSetup()
  const start = useStartImport()
  const job = useImportJob()
  const discard = useDiscardImport()
  const commit = useImportCommit()
  const startedFor = useRef<File[] | null>(null)
  const pct = useEasedProgress(job.data?.status === 'RUNNING' || start.isPending)
  const [open, setOpen] = useState<Set<string>>(new Set())
  const toggle = (kind: string) => setOpen((o) => { const n = new Set(o); if (n.has(kind)) n.delete(kind); else n.add(kind); return n })

  // Reading starts once, on the server; leaving this screen does not stop it and coming back finds it again.
  useEffect(() => {
    if (files.length === 0 || startedFor.current === files || job.isPending || job.data) return
    startedFor.current = files
    start.mutate(files)
  }, [files, job.isPending, job.data, start])

  const failure = start.isError ? start.error.message : job.data?.status === 'FAILED' ? job.data.message : null
  const retry = () => {
    startedFor.current = null
    start.reset()
    const id = job.data?.runId
    if (id) discard.mutate(id)
    else start.mutate(files)
  }
  if (failure) {
    return (
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">We could not read your CV</h1>
        <p role="alert" className="mt-3 rounded-lg bg-[var(--danger-soft)] px-4 py-3 text-sm text-[var(--danger)]">{failure}</p>
        <div className="mt-8 flex justify-between">
          <Button variant="ghost" onClick={() => { retry(); onBack() }}>Choose other files</Button>
          <Button size="lg" onClick={retry}>Try again</Button>
        </div>
      </div>
    )
  }

  const preview = job.data?.status === 'DONE' ? job.data.preview : null
  if (!preview) {
    return <Processing title="Reading your CV" percent={pct} etaSeconds={null} fallback="This usually takes a minute or two. You can look around meanwhile: it carries on." message="Splitting it into experience, education and skills." />
  }

  // Entries the database already has are not duplicated: their new facts are added to them.
  const fresh = preview.entries.filter((e) => e.matchEntryId === null)
  const merged = preview.entries.filter((e) => e.matchEntryId !== null)
  const addedFacts = merged.reduce((n, e) => n + e.newFacts, 0)
  const byKind = new Map<string, number>()
  fresh.forEach(({ entry }) => byKind.set(entry.kind, (byKind.get(entry.kind) ?? 0) + 1))
  const kinds = Object.keys(KIND_LABEL).filter((k) => k !== 'OTHER' || byKind.has(k)).map((k) => [k, byKind.get(k) ?? 0] as const)
  const facts = fresh.reduce((n, e) => n + e.entry.facts.length, 0) + addedFacts
  const who = preview.profile?.fullName

  const confirm = () => {
    startedFor.current = files // once confirmed, the same files must not be read again
    commit.mutate(
      {
        previewId: preview.previewId,
        entries: preview.entries.map((e) => ({ entry: e.entry, mergeIntoEntryId: e.matchEntryId })),
        skills: preview.skills,
        profile: preview.profile,
      },
      { onSuccess: () => { setFiles([]); onDone() } },
    )
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">{who ? `Here is what we found, ${who.split(' ')[0]}` : 'Here is what we found'}</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">
        {fresh.length} new {fresh.length === 1 ? 'entry' : 'entries'}, {facts} facts and {preview.skills.length} skills. You can correct anything
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

      {merged.length > 0 && (
        <p className="mt-3 text-sm text-[var(--muted)]">{merged.length} {merged.length === 1 ? 'entry was' : 'entries were'} already in your database: {addedFacts === 0 ? 'nothing new to add to them' : `${addedFacts} new ${addedFacts === 1 ? 'fact is' : 'facts are'} added to them`}.</p>
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
