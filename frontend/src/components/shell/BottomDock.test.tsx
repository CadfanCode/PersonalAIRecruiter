import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { BottomDock } from './BottomDock'

const dock = () => screen.getByRole('navigation', { name: 'More' })

describe('the menu at the bottom edge', () => {
  it('holds CV database, Country, Claude, Submitted applications, Extra features and Settings, hidden until wanted', () => {
    render(<MemoryRouter><BottomDock /></MemoryRouter>)

    const names = screen.getAllByRole('link').map((l) => l.textContent)
    expect(names).toEqual(['CV database', 'Country', 'Claude', 'Submitted applications', 'Extra features', 'Settings'])
    expect(dock().className).toContain('opacity-0')
    expect(dock().className).toContain('pointer-events-none')
  })

  it('does not depend on hover: the handle opens it, and Escape closes it', async () => {
    render(<MemoryRouter><BottomDock /></MemoryRouter>)
    const handle = screen.getByRole('button', { name: /More: CV database/ })
    expect(handle).toHaveAttribute('aria-expanded', 'false')

    await userEvent.click(handle)
    expect(handle).toHaveAttribute('aria-expanded', 'true')
    expect(dock().className).toContain('opacity-100')
    expect(dock().className).not.toContain('pointer-events-none translate-y')

    await userEvent.keyboard('{Escape}')
    expect(handle).toHaveAttribute('aria-expanded', 'false')
  })

  it('slides up when the keyboard reaches it: Tab to the handle opens it', async () => {
    render(<MemoryRouter><BottomDock /></MemoryRouter>)

    await userEvent.tab()

    expect(screen.getByRole('button', { name: /More: CV database/ })).toHaveFocus()
    expect(dock().className).toContain('opacity-100')
  })

  it('opens on the pointer going to the bottom edge', async () => {
    const { container } = render(<MemoryRouter><BottomDock /></MemoryRouter>)

    await userEvent.hover(container.querySelector('div[aria-hidden]') as HTMLElement)

    expect(dock().className).toContain('opacity-100')
  })

  it('marks the section of Settings you are on, not Settings as a whole', () => {
    render(<MemoryRouter initialEntries={['/settings/country']}><BottomDock /></MemoryRouter>)

    expect(screen.getByRole('link', { name: 'Country' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Settings' })).not.toHaveAttribute('aria-current')
  })

  it('marks the page you are on', () => {
    render(<MemoryRouter initialEntries={['/settings']}><BottomDock /></MemoryRouter>)

    expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'CV database' })).not.toHaveAttribute('aria-current')
  })
})
