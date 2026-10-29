import { useSearchSettings, useUpdateSearchSettings } from '../../api/settings'
import { Panel } from '../Panel'

/** What is searched: whether employers' own career pages are searched too (slower, off by default). */
export function SearchPanel() {
  const search = useSearchSettings()
  const update = useUpdateSearchSettings()
  return (
    <div className="grid gap-5">
      <Panel title="Where JobPilot looks">
        <p className="text-sm text-[var(--muted)]">The national job board of your country is always searched.</p>
        <label className="mt-4 flex items-start gap-3 text-sm">
          <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[var(--accent)]" checked={search.data?.careerPages ?? false} disabled={update.isPending}
            onChange={(e) => update.mutate(e.target.checked)} />
          <span>
            <span className="block font-medium">Also search employers' own career pages</span>
            <span className="block text-[var(--muted)]">Finds ads that are not on the job board. It is slower and uses noticeably more of your AI access, so it is off unless you want it.</span>
          </span>
        </label>
      </Panel>
    </div>
  )
}
