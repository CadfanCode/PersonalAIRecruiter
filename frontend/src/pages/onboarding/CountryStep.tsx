import { useCountry } from '../../api/country'
import { CountryPicker } from '../../components/CountryPicker'
import { Button } from '../../components/Form'

/** Step 3: where to search. Only Sweden has job sources built; the others are shown as coming soon. */
export function CountryStep({ onBack, onNext }: { onBack: () => void; onNext: () => void }) {
  const country = useCountry()
  const selected = country.data?.chosen ? country.data.selected : null
  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Where do you want to work?</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">
        The country decides which career pages and job boards JobPilot searches.
      </p>
      <div className="mt-7"><CountryPicker /></div>
      <div className="mt-8 flex justify-between">
        <Button variant="ghost" onClick={onBack}>Back</Button>
        <Button size="lg" onClick={onNext} disabled={!selected}>Continue</Button>
      </div>
    </div>
  )
}
