import { PageTitle, Panel } from '../components/Panel'

export function Placeholder({ title, milestone, description }: { title: string; milestone: number; description: string }) {
  return (
    <>
      <PageTitle>{title}</PageTitle>
      <Panel>
        <p className="text-sm text-[var(--muted)]">
          {description} Planned for milestone {milestone}.
        </p>
      </Panel>
    </>
  )
}
