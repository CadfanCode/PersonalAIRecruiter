import { useState } from 'react'
import { useDeleteSkill, useGaps, useSaveGaps, useSaveSkill, useSkills } from '../../api/cv'
import { Button, Field, TextArea, TextInput } from '../Form'
import { Panel } from '../Panel'

export function SkillsAndGaps() {
  const skills = useSkills()
  const gaps = useGaps()
  const saveSkill = useSaveSkill()
  const deleteSkill = useDeleteSkill()
  const saveGaps = useSaveGaps()
  const [name, setName] = useState('')
  const [category, setCategory] = useState('')
  const [gapText, setGapText] = useState<string | null>(null)

  const byCategory = new Map<string, NonNullable<typeof skills.data>>()
  for (const s of skills.data ?? []) {
    const key = s.category ?? 'other'
    byCategory.set(key, [...(byCategory.get(key) ?? []), s])
  }
  const gapValue = gapText ?? (gaps.data ?? []).map((g) => g.text).join('\n')

  return (
    <div className="grid gap-5">
      <Panel title="Skills">
        {[...byCategory.entries()].map(([cat, list]) => (
          <div key={cat} className="mb-3">
            <h3 className="mb-1 text-xs font-semibold uppercase text-[var(--muted)]">{cat}</h3>
            <ul className="flex flex-wrap gap-1.5">
              {list.map((s) => (
                <li key={s.id} className="flex items-center gap-1 rounded-full border border-[var(--line)] px-2.5 py-0.5 text-sm">
                  {s.name}
                  <button type="button" aria-label={`Delete skill ${s.name}`} onClick={() => deleteSkill.mutate(s.id)}>
                    ×
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ))}
        <div className="mt-3 flex items-end gap-2">
          <Field label="Skill">
            <TextInput value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Category">
            <TextInput value={category} onChange={(e) => setCategory(e.target.value)} />
          </Field>
          <Button
            disabled={!name.trim()}
            onClick={() =>
              saveSkill.mutate(
                { name: name.trim(), category: category.trim() || null },
                { onSuccess: () => { setName(''); setCategory('') } },
              )
            }
          >
            Add skill
          </Button>
        </div>
      </Panel>

      <Panel title="Known gaps">
        <p className="mb-2 text-xs text-[var(--muted)]">
          Honest limits, one per line. Letters state real gaps; scoring uses them.
        </p>
        <TextArea aria-label="Known gaps" rows={7} value={gapValue} onChange={(e) => setGapText(e.target.value)} />
        <div className="mt-3">
          <Button
            onClick={() => saveGaps.mutate(gapValue.split('\n'), { onSuccess: () => setGapText(null) })}
            disabled={saveGaps.isPending}
          >
            Save gaps
          </Button>
        </div>
      </Panel>
    </div>
  )
}
