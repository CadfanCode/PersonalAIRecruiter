import { useState } from 'react'
import {
  type Entry,
  type EntryInput,
  KINDS,
  useAddFact,
  useCreateEntry,
  useDeleteEntry,
  useUpdateEntry,
} from '../../api/cv'
import { Button, Field, TextArea, TextInput } from '../Form'
import { TagInput } from '../TagInput'
import { FactEditor, FactForm } from './FactEditor'

const EMPTY: EntryInput = {
  kind: 'WORK',
  title: '',
  titleEn: null,
  organisation: null,
  location: null,
  startYm: null,
  endYm: null,
  ongoing: false,
  summary: null,
  note: null,
  hidden: false,
  tags: [],
}

function toInput(e: Entry): EntryInput {
  const { id: _id, facts: _facts, ...rest } = e
  return rest
}

/** Edits one entry's own fields. Its facts have separate forms, so saving an entry never rewrites them. */
export function EntryEditor({
  entry,
  tagSuggestions,
  onDone,
}: {
  entry: Entry | null
  tagSuggestions: string[]
  onDone: () => void
}) {
  const [draft, setDraft] = useState<EntryInput>(entry ? toInput(entry) : EMPTY)
  const [addingFact, setAddingFact] = useState(false)
  const create = useCreateEntry()
  const update = useUpdateEntry()
  const remove = useDeleteEntry()
  const addFact = useAddFact()

  const set = <K extends keyof EntryInput>(key: K, value: EntryInput[K]) => setDraft({ ...draft, [key]: value })
  const text = (key: 'titleEn' | 'organisation' | 'location' | 'startYm' | 'endYm' | 'note') => ({
    value: draft[key] ?? '',
    onChange: (e: { target: { value: string } }) => set(key, e.target.value.trim() === '' ? null : e.target.value),
  })

  const error = (create.error ?? update.error ?? remove.error) as Error | null
  const save = () => {
    if (entry) update.mutate({ id: entry.id, input: draft }, { onSuccess: onDone })
    else create.mutate(draft, { onSuccess: onDone })
  }

  return (
    <div className="mt-3 flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3">
        <Field label="Kind">
          <select
            value={draft.kind}
            onChange={(e) => set('kind', e.target.value as EntryInput['kind'])}
            className="rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2.5 py-1.5 text-sm"
          >
            {KINDS.map((k) => (
              <option key={k}>{k}</option>
            ))}
          </select>
        </Field>
        <Field label="Organisation">
          <TextInput {...text('organisation')} />
        </Field>
        <Field label="Title (Svenska)">
          <TextInput value={draft.title} onChange={(e) => set('title', e.target.value)} />
        </Field>
        <Field label="Title (English)">
          <TextInput {...text('titleEn')} />
        </Field>
        <Field label="Location">
          <TextInput {...text('location')} />
        </Field>
        <div className="grid grid-cols-3 items-end gap-3">
          <Field label="Start (YYYY-MM or YYYY)">
            <TextInput {...text('startYm')} />
          </Field>
          <Field label="End">
            <TextInput {...text('endYm')} disabled={draft.ongoing} />
          </Field>
          <label className="flex items-center gap-2 pb-2 text-xs font-medium text-[var(--muted)]">
            <input type="checkbox" checked={draft.ongoing} onChange={(e) => set('ongoing', e.target.checked)} />
            Ongoing
          </label>
        </div>
        <TagInput label="Entry tags" value={draft.tags} onChange={(t) => set('tags', t)} suggestions={tagSuggestions} />
        <Field label="Note for me (never used in CVs or letters)" wide>
          <TextArea {...text('note')} />
        </Field>
        <label className="flex items-center gap-2 text-xs font-medium text-[var(--muted)]">
          <input type="checkbox" checked={draft.hidden} onChange={(e) => set('hidden', e.target.checked)} />
          Hidden (never selected for documents)
        </label>
      </div>

      {error && (
        <p role="alert" className="text-sm text-[var(--danger)]">
          {error.message}
        </p>
      )}
      <div className="flex gap-2">
        <Button onClick={save} disabled={create.isPending || update.isPending}>
          Save entry
        </Button>
        <Button variant="ghost" onClick={onDone}>
          Close
        </Button>
        {entry && (
          <Button
            variant="danger"
            onClick={() => {
              if (window.confirm(`Delete "${entry.title}" and its ${entry.facts.length} facts?`)) {
                remove.mutate(entry.id, { onSuccess: onDone })
              }
            }}
          >
            Delete entry
          </Button>
        )}
      </div>

      {entry && (
        <section aria-label="Facts" className="flex flex-col gap-3">
          <h3 className="text-sm font-semibold">Facts ({entry.facts.length})</h3>
          {entry.facts.map((f) => (
            <FactEditor key={`${f.id}-${f.textEn}-${f.textSv}-${f.tags.join()}`} fact={f} tagSuggestions={tagSuggestions} />
          ))}
          {addingFact ? (
            <FactForm
              initial={{ textEn: null, textSv: null, evidence: null, metrics: null, tags: [] }}
              tagSuggestions={tagSuggestions}
              saving={addFact.isPending}
              onSave={(input) => addFact.mutate({ entryId: entry.id, input }, { onSuccess: () => setAddingFact(false) })}
            />
          ) : (
            <div>
              <Button variant="ghost" onClick={() => setAddingFact(true)}>
                Add fact
              </Button>
            </div>
          )}
        </section>
      )}
    </div>
  )
}
