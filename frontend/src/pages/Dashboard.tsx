import { ClaudeHealthCard } from '../components/ClaudeHealthCard'
import { NewRunForm } from '../components/NewRunForm'
import { PageTitle } from '../components/Panel'
import { RecentRuns } from '../components/RecentRuns'

export function Dashboard() {
  return (
    <>
      <PageTitle>Dashboard</PageTitle>
      <div className="grid max-w-4xl gap-5">
        <NewRunForm />
        <RecentRuns />
        <ClaudeHealthCard />
      </div>
    </>
  )
}
