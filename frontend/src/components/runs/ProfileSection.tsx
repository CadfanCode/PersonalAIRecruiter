import type { SearchProfile } from '../../api/runs'
import { Button } from '../Form'
import { SearchProfileCard } from './SearchProfileCard'

/** The search plan, with Save and "Approve and search" while the run waits for the user's review. */
export function ProfileSection({
  profile,
  waiting,
  saving,
  error,
  onSave,
  onContinue,
  continuing,
  showContinue = true,
}: {
  profile: SearchProfile
  waiting: boolean
  saving: boolean
  error: string | null
  onSave: (p: SearchProfile) => void
  onContinue: () => void
  continuing: boolean
  /** A Discover run continues from the role picker instead. */
  showContinue?: boolean
}) {
  return (
    <>
      <SearchProfileCard key={JSON.stringify(profile)} profile={profile} editable={waiting} saving={saving} onSave={onSave} />
      {error && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{error}</p>}
      {waiting && showContinue && (
        <div className="mt-5 flex flex-wrap items-center gap-4">
          <Button size="lg" onClick={onContinue} disabled={continuing}>{continuing ? 'Starting…' : 'Approve and search'}</Button>
          <span className="max-w-md text-sm text-[var(--muted)]">Check the titles, places and search words first: they decide what is found. Approving starts the search.</span>
        </div>
      )}
    </>
  )
}
