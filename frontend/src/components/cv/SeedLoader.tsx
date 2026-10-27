import { useImportSeed } from '../../api/cv'
import { Panel } from '../Panel'

/** Shown while the database is empty: loads seed/cv_database_seed.json. */
export function SeedLoader() {
  const seed = useImportSeed()
  return (
    <Panel title="Your CV database is empty">
      <p className="mb-3 text-sm text-[var(--muted)]">
        Load the seed file (<code>seed/cv_database_seed.json</code>), or add entries by hand, or import a CV.
      </p>
      <input
        type="file"
        accept=".json,application/json"
        aria-label="Seed file"
        onChange={async (e) => {
          const file = e.target.files?.[0]
          if (file) seed.mutate(await file.text())
        }}
      />
      {seed.isError && (
        <p role="alert" className="mt-3 text-sm text-red-600">
          {seed.error.message}
        </p>
      )}
    </Panel>
  )
}
