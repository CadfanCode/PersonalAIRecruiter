import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react'

export type ThemePreference = 'system' | 'light' | 'dark'

const KEY = 'jobpilot.theme'

function read(): ThemePreference {
  try {
    const saved = localStorage.getItem(KEY)
    return saved === 'light' || saved === 'dark' ? saved : 'system'
  } catch {
    return 'system'
  }
}

const systemPrefersDark = () => typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches

/** What the screen shows for a choice: "system" means whatever the operating system is set to right now. */
export const resolveTheme = (preference: ThemePreference): 'light' | 'dark' =>
  preference === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : preference

/** Puts the resolved theme on <html data-theme>, which every colour variable keys off. */
export function applyTheme(preference: ThemePreference) {
  document.documentElement.setAttribute('data-theme', resolveTheme(preference))
}

interface ThemeContextValue {
  preference: ThemePreference
  setPreference: (p: ThemePreference) => void
}

const ThemeContext = createContext<ThemeContextValue>({ preference: 'system', setPreference: () => {} })

export const useTheme = () => useContext(ThemeContext)

/** Light, dark or follow the system (the default). The choice is remembered on this computer only. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setState] = useState<ThemePreference>(read)

  useEffect(() => {
    applyTheme(preference)
    if (preference !== 'system' || typeof window.matchMedia !== 'function') return
    // Following the system: switch when the system does.
    const query = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => applyTheme('system')
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [preference])

  const setPreference = useCallback((p: ThemePreference) => {
    setState(p)
    try {
      if (p === 'system') localStorage.removeItem(KEY)
      else localStorage.setItem(KEY, p)
    } catch {
      // storage can be blocked; the choice then only lasts for this session
    }
  }, [])

  const value = useMemo(() => ({ preference, setPreference }), [preference, setPreference])
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}
