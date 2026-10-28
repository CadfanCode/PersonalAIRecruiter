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

/** Puts the choice on <html>: "system" removes the attribute so the media query decides. */
export function applyTheme(preference: ThemePreference) {
  const root = document.documentElement
  if (preference === 'system') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', preference)
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

  useEffect(() => applyTheme(preference), [preference])

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
