import { ClaudeHealthCard } from '../components/ClaudeHealthCard'
import { PageTitle, Panel } from '../components/Panel'

const examples = ['New-grad Java, Stockholm, 7 days', 'PE teacher, Nacka', 'Logistics, Uppsala, part time']

export function Dashboard() {
  return (
    <>
      <PageTitle>Dashboard</PageTitle>
      <div className="grid max-w-4xl gap-5">
        <Panel title="New run">
          <textarea
            aria-label="Starting prompt"
            disabled
            rows={3}
            placeholder="Describe the roles you want, for example: new-grad Java roles in Stockholm posted in the last 7 days"
            className="w-full rounded-md border border-[var(--line)] bg-[var(--surface)] p-3 text-sm disabled:opacity-60"
          />
          <div className="mt-3 flex flex-wrap items-center gap-2">
            {examples.map((text) => (
              <span key={text} className="rounded-full border border-[var(--line)] px-3 py-1 text-xs text-[var(--muted)]">
                {text}
              </span>
            ))}
          </div>
          <p className="mt-3 text-xs text-[var(--muted)]">Search and ranking arrive in milestone 3.</p>
        </Panel>
        <ClaudeHealthCard />
      </div>
    </>
  )
}
