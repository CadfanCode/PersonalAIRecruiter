import { useState } from 'react'
import { useEntries, useProfile, useTags } from '../api/cv'
import { EntryList } from '../components/cv/EntryList'
import { ImportWizard } from '../components/cv/ImportWizard'
import { PreviewSelection } from '../components/cv/PreviewSelection'
import { ProfileForm } from '../components/cv/ProfileForm'
import { SeedLoader } from '../components/cv/SeedLoader'
import { SkillsAndGaps } from '../components/cv/SkillsAndGaps'
import { PageTitle } from '../components/Panel'

const TABS = ['Entries', 'Profile', 'Skills and gaps', 'Import', 'Preview selection'] as const

export function CvDatabase() {
  const [tab, setTab] = useState<(typeof TABS)[number]>('Entries')
  const entries = useEntries()
  const profile = useProfile()
  const tags = useTags()

  if (entries.isError || profile.isError) {
    return (
      <>
        <PageTitle>CV database</PageTitle>
        <p role="alert" className="text-sm text-[var(--danger)]">Cannot reach the JobPilot API. Is the backend running?</p>
      </>
    )
  }
  if (!entries.data || !profile.data) return <PageTitle>CV database</PageTitle>

  const empty = entries.data.length === 0 && profile.data.id === null
  const tagNames = (tags.data ?? []).map((t) => t.name)

  return (
    <>
      <PageTitle>CV database</PageTitle>
      {empty && (
        <div className="mb-5 max-w-3xl">
          <SeedLoader />
        </div>
      )}
      <div role="tablist" className="mb-5 flex gap-1 border-b border-[var(--line)]">
        {TABS.map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
              tab === t ? 'border-[var(--accent)] text-[var(--accent)]' : 'border-transparent text-[var(--muted)]'
            }`}
          >
            {t}
          </button>
        ))}
      </div>
      <div className="max-w-4xl">
        {tab === 'Entries' && <EntryList entries={entries.data} tagSuggestions={tagNames} />}
        {tab === 'Profile' && <ProfileForm key={profile.data.id ?? 'new'} profile={profile.data} />}
        {tab === 'Skills and gaps' && <SkillsAndGaps />}
        {tab === 'Import' && <ImportWizard />}
        {tab === 'Preview selection' && <PreviewSelection />}
      </div>
    </>
  )
}
