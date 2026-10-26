import { useState } from 'react'
import type { SearchProfile } from '../../api/runs'
import { Button, Field, TextInput } from '../Form'
import { Panel } from '../Panel'
import { TagInput } from '../TagInput'

const SENIORITY = ['student', 'graduate', 'junior', 'mid', 'senior', 'any'] as const

/**
 * The SearchProfile every later step reads. Editable while the run waits for review; read-only
 * afterwards. Municipalities are edited as chips; each becomes one location with radius 0.
 */
export function SearchProfileCard({
  profile,
  editable,
  saving,
  onSave,
}: {
  profile: SearchProfile
  editable: boolean
  saving?: boolean
  onSave: (p: SearchProfile) => void
}) {
  const [draft, setDraft] = useState(profile)
  const set = <K extends keyof SearchProfile>(key: K, value: SearchProfile[K]) => setDraft({ ...draft, [key]: value })
  const dirty = JSON.stringify(draft) !== JSON.stringify(profile)

  const chips = (label: string, key: 'titles' | 'synonyms' | 'skillsWanted' | 'relevantTags' | 'exclusions' | 'jobtechQueries') =>
    editable ? (
      <TagInput label={label} value={draft[key]} onChange={(v) => set(key, v)} />
    ) : (
      <ReadOnlyChips label={label} values={draft[key]} />
    )

  return (
    <Panel title="Search profile">
      <p className="mb-3 text-sm text-[var(--muted)]">{draft.roleSummary}</p>
      <div className="grid grid-cols-2 gap-3">
        {chips('Titles', 'titles')}
        {chips('Skills', 'skillsWanted')}
        {chips('Relevant CV tags', 'relevantTags')}
        {chips('Search queries', 'jobtechQueries')}
        {chips('Exclusions (words in the title)', 'exclusions')}
        {editable ? (
          <TagInput
            label="Municipalities"
            value={draft.locations.map((l) => l.municipality)}
            onChange={(names) =>
              set('locations', names.map((n) => draft.locations.find((l) => l.municipality.toLowerCase() === n) ?? {
                name: n, municipality: n.charAt(0).toUpperCase() + n.slice(1), radiusKm: 0,
              }))
            }
          />
        ) : (
          <ReadOnlyChips label="Municipalities" values={draft.locations.map((l) => l.municipality)} />
        )}
        <Field label="Seniority">
          <select
            disabled={!editable}
            value={draft.seniority}
            onChange={(e) => set('seniority', e.target.value as SearchProfile['seniority'])}
            className="rounded-md border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1.5 text-sm"
          >
            {SENIORITY.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Yrkesroll for aktivitetsrapport">
          <TextInput
            disabled={!editable}
            value={draft.yrkesrollForAktivitetsrapport}
            onChange={(e) => set('yrkesrollForAktivitetsrapport', e.target.value)}
          />
        </Field>
        <Field label="Max age of ads (days)">
          <TextInput
            type="number"
            min={1}
            max={90}
            disabled={!editable}
            value={draft.maxAgeDays}
            onChange={(e) => set('maxAgeDays', Number(e.target.value))}
          />
        </Field>
        <label className="flex items-center gap-2 self-end pb-2 text-xs font-medium text-[var(--muted)]">
          <input type="checkbox" disabled={!editable} checked={draft.remoteOk} onChange={(e) => set('remoteOk', e.target.checked)} />
          Remote work is fine
        </label>
      </div>
      {editable && (
        <div className="mt-4">
          <Button onClick={() => onSave(draft)} disabled={!dirty || saving}>
            Save profile
          </Button>
        </div>
      )}
    </Panel>
  )
}

function ReadOnlyChips({ label, values }: { label: string; values: string[] }) {
  return (
    <div className="col-span-full flex flex-col gap-1 text-xs font-medium text-[var(--muted)]">
      <span>{label}</span>
      <span className="flex flex-wrap gap-1.5">
        {values.length === 0 && <span className="text-[var(--muted)]">none</span>}
        {values.map((v) => (
          <span key={v} className="rounded-full bg-navy/10 px-2 py-0.5 text-xs text-[var(--accent)]">
            {v}
          </span>
        ))}
      </span>
    </div>
  )
}
