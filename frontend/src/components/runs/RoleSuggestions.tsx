import { useState } from 'react'
import {
  DISCOVER_AGGRESSIVE_LIMIT,
  DISCOVER_LIMIT,
  type RoleCategory,
  type RoleEdit,
  type RoleView,
  type RunOptions,
} from '../../api/runs'
import { Button, TextInput } from '../Form'
import { Panel } from '../Panel'

const GROUPS: { key: RoleCategory; title: string; hint: string }[] = [
  { key: 'RELATED', title: 'Closely related roles', hint: 'Other roles in your main field that fit your profile' },
  { key: 'HYBRID', title: 'Hybrid roles', hint: 'Roles that combine parts of your background' },
  { key: 'OUTSIDE_FIELD', title: 'Outside your main field', hint: 'Roles where your other education and experience make you a strong candidate' },
  { key: 'CUSTOM', title: 'Your own roles', hint: '' },
]

interface Draft {
  key: string
  id?: number
  title: string
  selected: boolean
  role?: RoleView
}

const fromRoles = (roles: RoleView[]): Draft[] =>
  roles.map((r) => ({ key: `r${r.id}`, id: r.id, title: r.title, selected: r.selected, role: r }))

/**
 * The role types Claude suggests from the whole CV. While the run waits the user ticks the ones to search (or all of them),
 * renames, and types roles of their own. Afterwards the same list stays on the run, read only, with what each role found.
 */
export function RoleSuggestions({
  roles,
  options,
  waiting,
  saving,
  error,
  onSearch,
}: {
  roles: RoleView[]
  options: RunOptions
  waiting: boolean
  saving: boolean
  error: string | null
  onSearch: (edits: RoleEdit[]) => void
}) {
  const [drafts, setDrafts] = useState<Draft[]>(() => fromRoles(roles))
  const [editing, setEditing] = useState<string | null>(null)
  const [added, setAdded] = useState('')

  if (!waiting) return <ReadOnlyRoles roles={roles} />

  const chosen = drafts.filter((d) => d.selected)
  const allSelected = drafts.length > 0 && chosen.length === drafts.length
  const cap = options.limit ?? (options.aggressive ? DISCOVER_AGGRESSIVE_LIMIT : DISCOVER_LIMIT)
  const patch = (key: string, change: Partial<Draft>) => setDrafts(drafts.map((d) => (d.key === key ? { ...d, ...change } : d)))
  const add = () => {
    const title = added.trim()
    if (!title || drafts.some((d) => d.title.toLowerCase() === title.toLowerCase())) return
    setDrafts([...drafts, { key: `n${Date.now()}`, title, selected: true }])
    setAdded('')
  }

  return (
    <Panel title="Roles you could be a strong match for">
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-2 text-sm font-medium">
          <input type="checkbox" checked={allSelected} onChange={(e) => setDrafts(drafts.map((d) => ({ ...d, selected: e.target.checked })))} />
          Select all ({drafts.length})
        </label>
        <span className="text-sm text-[var(--muted)]">{chosen.length} selected</span>
        <span className="ml-auto text-xs text-[var(--muted)]">
          Up to {cap} ads are scored by Claude{options.aggressive ? ' (aggressive mode: wider search, lower bar)' : ''}. This uses your Claude access.
        </span>
      </div>

      {GROUPS.map((group) => {
        const items = drafts.filter((d) => (d.role?.category ?? 'CUSTOM') === group.key)
        if (items.length === 0) return null
        return (
          <section key={group.key} aria-label={group.title} className="mb-5">
            <h3 className="text-sm font-semibold">{group.title}</h3>
            {group.hint && <p className="mb-2 text-xs text-[var(--muted)]">{group.hint}</p>}
            <ul className="flex flex-col gap-2">
              {items.map((d) => (
                <li key={d.key} className={`rounded-md border p-3 ${d.selected ? 'border-[var(--accent)] bg-navy/5' : 'border-[var(--line)]'}`}>
                  <div className="flex items-center gap-3">
                    <input type="checkbox" aria-label={`Search for ${d.title}`} checked={d.selected} onChange={(e) => patch(d.key, { selected: e.target.checked })} />
                    {editing === d.key ? (
                      <TextInput aria-label={`Rename ${d.role?.title ?? d.title}`} autoFocus value={d.title} onChange={(e) => patch(d.key, { title: e.target.value })}
                        onBlur={() => setEditing(null)} onKeyDown={(e) => e.key === 'Enter' && setEditing(null)} />
                    ) : (
                      <span className="font-medium">{d.title}</span>
                    )}
                    {d.role?.matchScore != null && <MatchPill score={d.role.matchScore} />}
                    <span className="ml-auto flex gap-2">
                      {editing !== d.key && <Button variant="ghost" aria-label={`Edit ${d.title}`} onClick={() => setEditing(d.key)}>Edit</Button>}
                      {!d.role && <Button variant="ghost" aria-label={`Remove ${d.title}`} onClick={() => setDrafts(drafts.filter((x) => x.key !== d.key))}>Remove</Button>}
                    </span>
                  </div>
                  {d.role?.reasoning && <p className="mt-2 text-sm text-[var(--muted)]">{d.role.reasoning}</p>}
                  {d.role?.gaps && <p className="mt-1 text-xs text-[var(--warn)]">Gaps: {d.role.gaps}</p>}
                  {d.role && d.title !== d.role.title && <p className="mt-1 text-xs text-[var(--muted)]">Renamed: its search terms are rebuilt for the new name.</p>}
                </li>
              ))}
            </ul>
          </section>
        )
      })}

      <div className="mb-4 flex items-center gap-2">
        <TextInput aria-label="Add your own role" placeholder="Add a role of your own, for example Behandlingsassistent" value={added}
          onChange={(e) => setAdded(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && add()} />
        <Button variant="ghost" onClick={add} disabled={!added.trim()}>Add role</Button>
      </div>

      <div className="flex items-center gap-3">
        <Button disabled={chosen.length === 0 || saving}
          onClick={() => onSearch(drafts.map((d) => ({ ...(d.id !== undefined ? { id: d.id } : {}), title: d.title.trim(), selected: d.selected })))}>
          {saving ? 'Starting...' : `Search ${chosen.length} selected ${chosen.length === 1 ? 'role' : 'roles'}`}
        </Button>
        <span className="text-sm text-[var(--muted)]">Everything found is merged into one ranked list, tagged with its role.</span>
      </div>
      {error && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{error}</p>}
    </Panel>
  )
}

function MatchPill({ score }: { score: number }) {
  const tone = score >= 70 ? 'bg-[var(--success-soft)] text-[var(--success)]' : score >= 50 ? 'bg-[var(--warn-soft)] text-[var(--warn)]' : 'bg-[var(--line)] text-[var(--muted)]'
  return <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${tone}`} aria-label={`Match ${score} out of 100`}>match {score}</span>
}

/** After the search: the suggestions stay with the run, with which roles were searched and how many ads each found. */
function ReadOnlyRoles({ roles }: { roles: RoleView[] }) {
  const searched = roles.filter((r) => r.searched)
  return (
    <details>
      <summary className="mb-2 cursor-pointer text-sm font-medium text-[var(--muted)]">
        Role suggestions for this run ({searched.length} of {roles.length} searched)
      </summary>
      <ul className="flex max-w-4xl flex-col gap-2">
        {roles.map((r) => (
          <li key={r.id} className={`rounded-md border border-[var(--line)] p-3 text-sm ${r.searched ? '' : 'opacity-60'}`}>
            <div className="flex flex-wrap items-center gap-3">
              <span className="font-medium">{r.title}</span>
              {r.matchScore != null && <MatchPill score={r.matchScore} />}
              <span className="ml-auto text-xs text-[var(--muted)]">{r.searched ? `${r.jobCount} ads found` : 'not searched'}</span>
            </div>
            {r.reasoning && <p className="mt-1 text-[var(--muted)]">{r.reasoning}</p>}
            {r.gaps && <p className="mt-1 text-xs text-[var(--warn)]">Gaps: {r.gaps}</p>}
          </li>
        ))}
      </ul>
    </details>
  )
}
