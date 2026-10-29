import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThemeProvider, useTheme } from './ThemeProvider'

function Probe() {
  const { theme, toggle } = useTheme()
  return (
    <div>
      <span data-testid="theme">{theme}</span>
      <button onClick={toggle}>toggle</button>
    </div>
  )
}

const mockSystemDark = () =>
  vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('dark'), addEventListener: () => {}, removeEventListener: () => {} }))

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})

describe('theme', () => {
  it('is light by default, even when the operating system is set to dark', () => {
    mockSystemDark()
    render(<ThemeProvider><Probe /></ThemeProvider>)

    expect(screen.getByTestId('theme')).toHaveTextContent('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('toggles to dark and back, puts the theme on the page, and remembers only dark', async () => {
    render(<ThemeProvider><Probe /></ThemeProvider>)

    await userEvent.click(screen.getByRole('button', { name: 'toggle' }))
    expect(localStorage.getItem('jobpilot.theme')).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

    await userEvent.click(screen.getByRole('button', { name: 'toggle' }))
    expect(localStorage.getItem('jobpilot.theme')).toBeNull()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('starts from the saved choice', () => {
    localStorage.setItem('jobpilot.theme', 'dark')
    render(<ThemeProvider><Probe /></ThemeProvider>)

    expect(screen.getByTestId('theme')).toHaveTextContent('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })
})
