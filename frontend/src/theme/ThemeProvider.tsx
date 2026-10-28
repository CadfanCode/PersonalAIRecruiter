import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react'

export type Theme = 'light' | 'dark'

const KEY = 'jobpilot.theme'

/** Light unless the user has chosen dark: the operating system's setting is deliberately not consulted. */
function read(): Theme {
  try {
    return localStorage.getItem(KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

/** Puts the theme on <html data-theme>, which every colour variable keys off. */
export function applyTheme(theme: Theme) {
  document.documentElement.setAttribute('data-theme', theme)
}

interface ThemeContextValue {
  theme: Theme
  setTheme: (t: Theme) => void
  toggle: () => void
}

const ThemeContext = createContext<ThemeContextValue>({ theme: 'light', setTheme: () => {}, toggle: () => {} })

export const useTheme = () => useContext(ThemeContext)

/** Light by default, with a dark option in the top right corner. The choice is remembered on this computer only. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setState] = useState<Theme>(read)

  useEffect(() => applyTheme(theme), [theme])

  const setTheme = useCallback((t: Theme) => {
    setState(t)
    try {
      if (t === 'light') localStorage.removeItem(KEY)
      else localStorage.setItem(KEY, t)
    } catch {
      // storage can be blocked; the choice then only lasts for this session
    }
  }, [])
  const toggle = useCallback(() => setTheme(theme === 'dark' ? 'light' : 'dark'), [theme, setTheme])

  const value = useMemo(() => ({ theme, setTheme, toggle }), [theme, setTheme, toggle])
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}
