import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { applyTheme, resolveTheme, ThemeProvider, useTheme } from './ThemeProvider'

function Probe() {
  const { preference, setPreference } = useTheme()
  return (
    <div>
      <span data-testid="pref">{preference}</span>
      {(['system', 'light', 'dark'] as const).map((p) => <button key={p} onClick={() => setPreference(p)}>{p}</button>)}
    </div>
  )
}

const mockSystem = (dark: boolean) =>
  vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('dark') && dark, addEventListener: () => {}, removeEventListener: () => {} }))

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})

describe('theme', () => {
  it('follows the system by default, and the system can be dark or light', () => {
    mockSystem(true)
    expect(resolveTheme('system')).toBe('dark')
    mockSystem(false)
    expect(resolveTheme('system')).toBe('light')
    expect(resolveTheme('dark')).toBe('dark')
  })

  it('always puts the resolved theme on the page so every colour and dark: class follows it', () => {
    mockSystem(true)
    applyTheme('system')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    applyTheme('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('remembers an explicit choice on this computer and forgets it when the user goes back to the system', async () => {
    mockSystem(false)
    render(<ThemeProvider><Probe /></ThemeProvider>)
    expect(screen.getByTestId('pref')).toHaveTextContent('system')

    await userEvent.click(screen.getByRole('button', { name: 'dark' }))
    expect(localStorage.getItem('jobpilot.theme')).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    await userEvent.click(screen.getByRole('button', { name: 'system' }))
    expect(localStorage.getItem('jobpilot.theme')).toBeNull()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('starts from the saved choice', () => {
    mockSystem(false)
    localStorage.setItem('jobpilot.theme', 'dark')
    render(<ThemeProvider><Probe /></ThemeProvider>)

    expect(screen.getByTestId('pref')).toHaveTextContent('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })
})
