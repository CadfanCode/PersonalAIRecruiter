import { type ThemePreference, useTheme } from '../../theme/ThemeProvider'
import { Panel } from '../Panel'

const OPTIONS: [ThemePreference, string, string][] = [
  ['system', 'Same as your computer', 'Follows light and dark mode automatically.'],
  ['light', 'Light', 'Always light.'],
  ['dark', 'Dark', 'Always dark.'],
]

export function AppearancePanel() {
  const { preference, setPreference } = useTheme()
  return (
    <Panel title="Appearance">
      <div role="radiogroup" aria-label="Theme" className="grid gap-3 sm:grid-cols-3">
        {OPTIONS.map(([id, label, text]) => (
          <button key={id} type="button" role="radio" aria-checked={preference === id} onClick={() => setPreference(id)}
            className={`rounded-2xl border-2 p-4 text-left transition ${preference === id ? 'border-[var(--accent)] bg-[var(--accent-soft)]' : 'border-[var(--line)] hover:border-[var(--muted)]/50'}`}>
            <span className="block text-sm font-semibold">{label}</span>
            <span className="mt-1 block text-xs text-[var(--muted)]">{text}</span>
          </button>
        ))}
      </div>
    </Panel>
  )
}
