import { useState } from 'react'
import { type Fact, type FactInput, useDeleteFact, useUpdateFact } from '../../api/cv'
import { Button, Field, TextArea, TextInput } from '../Form'
import { TagInput } from '../TagInput'

export function FactForm({
  initial,
  tagSuggestions,
  onSave,
  onDelete,
  saving,
}: {
  initial: FactInput
  tagSuggestions: string[]
  onSave: (input: FactInput) => void
  onDelete?: () => void
  saving?: boolean
}) {
  const [draft, setDraft] = useState(initial)
  const set = <K extends keyof FactInput>(key: K, value: FactInput[K]) => setDraft({ ...draft, [key]: value })
  const blank = (s: string) => (s.trim() === '' ? null : s)

  return (
    <div className="grid grid-cols-2 gap-3 rounded-md border border-[var(--line)] p-3">
      <Field label="English">
        <TextArea value={draft.textEn ?? ''} onChange={(e) => set('textEn', blank(e.target.value))} />
      </Field>
      <Field label="Svenska">
        <TextArea value={draft.textSv ?? ''} onChange={(e) => set('textSv', blank(e.target.value))} />
      </Field>
      <Field label="Evidence (where this can be verified)">
        <TextInput value={draft.evidence ?? ''} onChange={(e) => set('evidence', blank(e.target.value))} />
      </Field>
      <Field label="Metrics">
        <TextInput value={draft.metrics ?? ''} onChange={(e) => set('metrics', blank(e.target.value))} />
      </Field>
      <TagInput label="Fact tags" value={draft.tags} onChange={(t) => set('tags', t)} suggestions={tagSuggestions} />
      <div className="col-span-full flex gap-2">
        <Button onClick={() => onSave(draft)} disabled={saving}>
          Save fact
        </Button>
        {onDelete && (
          <Button variant="danger" onClick={onDelete}>
            Delete fact
          </Button>
        )}
      </div>
    </div>
  )
}

export function FactEditor({ fact, tagSuggestions }: { fact: Fact; tagSuggestions: string[] }) {
  const update = useUpdateFact()
  const remove = useDeleteFact()
  return (
    <FactForm
      initial={{ textEn: fact.textEn, textSv: fact.textSv, evidence: fact.evidence, metrics: fact.metrics, tags: fact.tags }}
      tagSuggestions={tagSuggestions}
      saving={update.isPending}
      onSave={(input) => update.mutate({ id: fact.id, input })}
      onDelete={() => remove.mutate(fact.id)}
    />
  )
}
