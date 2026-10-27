import type { SearchProfile } from '../../api/runs'
import { Button } from '../Form'
import { SearchProfileCard } from './SearchProfileCard'

/** The profile card, with Save and "Continue to search" while the run waits for review. */
export function ProfileSection({
  profile,
  waiting,
  saving,
  error,
  onSave,
  onContinue,
  continuing,
}: {
  profile: SearchProfile
  waiting: boolean
  saving: boolean
  error: string | null
  onSave: (p: SearchProfile) => void
  onContinue: () => void
  continuing: boolean
}) {
  return (
    <>
      <SearchProfileCard key={JSON.stringify(profile)} profile={profile} editable={waiting} saving={saving} onSave={onSave} />
      {error && <p role="alert" className="mt-2 text-sm text-red-600">{error}</p>}
      {waiting && (
        <div className="mt-4 flex items-center gap-3">
          <Button onClick={onContinue} disabled={continuing}>Continue to search</Button>
          <span className="text-sm text-[var(--muted)]">Check the titles, municipalities and queries first: they decide what is found.</span>
        </div>
      )}
    </>
  )
}
