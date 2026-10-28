import { useTheme } from '../../theme/ThemeProvider'
import { MoonIcon, SunIcon } from '../ui/Icons'

/** Light is the default; this switches to dark and back. It lives in the top right corner on every screen. */
export function ThemeToggle() {
  const { theme, toggle } = useTheme()
  const dark = theme === 'dark'
  return (
    <button type="button" role="switch" aria-checked={dark} aria-label="Dark mode" onClick={toggle} title={dark ? 'Switch to light mode' : 'Switch to dark mode'}
      className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-md border border-[var(--line)] text-[var(--muted)] transition-colors hover:bg-[var(--hover)] hover:text-[var(--ink)]">
      {dark ? <SunIcon /> : <MoonIcon />}
    </button>
  )
}
