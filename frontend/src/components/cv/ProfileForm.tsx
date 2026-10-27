import { useState } from 'react'
import { type Language, type Profile, useSaveProfile } from '../../api/cv'
import { Button, Field, TextInput } from '../Form'
import { Panel } from '../Panel'

type TextKey = Exclude<keyof Profile, 'id' | 'languages' | 'fullName'>

const FIELDS: [TextKey, string][] = [
  ['headline', 'Headline'],
  ['email', 'Email'],
  ['phone', 'Phone'],
  ['phoneInternational', 'Phone (international)'],
  ['city', 'City'],
  ['website', 'Website'],
  ['github', 'GitHub'],
  ['linkedin', 'LinkedIn'],
  ['workAuthorisation', 'Work authorisation'],
  ['securityVetting', 'Security vetting'],
]

export function ProfileForm({ profile }: { profile: Profile }) {
  const [draft, setDraft] = useState(profile)
  const save = useSaveProfile()
  const setLanguage = (i: number, patch: Partial<Language>) =>
    setDraft({ ...draft, languages: draft.languages.map((l, j) => (j === i ? { ...l, ...patch } : l)) })

  return (
    <Panel title="Profile">
      <div className="grid grid-cols-2 gap-3">
        <Field label="Full name">
          <TextInput value={draft.fullName} onChange={(e) => setDraft({ ...draft, fullName: e.target.value })} />
        </Field>
        {FIELDS.map(([key, label]) => (
          <Field key={key} label={label}>
            <TextInput
              value={draft[key] ?? ''}
              onChange={(e) => setDraft({ ...draft, [key]: e.target.value === '' ? null : e.target.value })}
            />
          </Field>
        ))}
        <fieldset className="col-span-full flex flex-col gap-2">
          <legend className="mb-1 text-xs font-medium text-[var(--muted)]">Languages</legend>
          {draft.languages.map((l, i) => (
            <div key={i} className="flex gap-2">
              <TextInput aria-label={`Language ${i + 1}`} value={l.language} onChange={(e) => setLanguage(i, { language: e.target.value })} />
              <TextInput aria-label={`Level ${i + 1}`} value={l.level} onChange={(e) => setLanguage(i, { level: e.target.value })} />
              <Button variant="ghost" onClick={() => setDraft({ ...draft, languages: draft.languages.filter((_, j) => j !== i) })}>
                Remove
              </Button>
            </div>
          ))}
          <div>
            <Button variant="ghost" onClick={() => setDraft({ ...draft, languages: [...draft.languages, { language: '', level: '' }] })}>
              Add language
            </Button>
          </div>
        </fieldset>
      </div>
      <div className="mt-4 flex items-center gap-3">
        <Button onClick={() => save.mutate(draft)} disabled={save.isPending}>
          Save profile
        </Button>
        {save.isSuccess && <span role="status" className="text-sm text-emerald-600">Saved</span>}
        {save.isError && <span role="alert" className="text-sm text-red-600">{save.error.message}</span>}
      </div>
    </Panel>
  )
}
