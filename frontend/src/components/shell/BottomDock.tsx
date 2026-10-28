import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ArchiveIcon, GridIcon, SlidersIcon, UserIcon } from '../ui/Icons'

const ITEMS = [
  { to: '/cv', label: 'CV database', icon: UserIcon },
  { to: '/submitted', label: 'Submitted applications', icon: ArchiveIcon },
  { to: '/extras', label: 'Extra features', icon: GridIcon },
  { to: '/settings', label: 'Settings', icon: SlidersIcon },
] as const

/**
 * The menu that is out of the way until it is wanted: it slides up when the pointer goes to the bottom edge, when the little
 * handle is clicked or focused, or when Tab reaches one of its links. It never depends on hover alone.
 */
export function BottomDock() {
  const [open, setOpen] = useState(false)
  const timer = useRef<number | undefined>(undefined)
  const { pathname } = useLocation()

  const show = useCallback(() => {
    window.clearTimeout(timer.current)
    setOpen(true)
  }, [])
  const hideSoon = useCallback(() => {
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setOpen(false), 450)
  }, [])

  useEffect(() => setOpen(false), [pathname])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.clearTimeout(timer.current)
    }
  }, [])

  return (
    <>
      <div aria-hidden className="fixed inset-x-0 bottom-0 z-40 h-4" onMouseEnter={show} />
      <button type="button" aria-label="More: CV database, submitted applications, extra features, settings" aria-expanded={open} aria-controls="more-menu"
        onClick={() => setOpen((o) => !o)} onMouseEnter={show} onFocus={show}
        className={`fixed bottom-1.5 left-1/2 z-40 h-1.5 w-28 -translate-x-1/2 rounded-full bg-[var(--muted)]/35 transition hover:bg-[var(--muted)]/60 ${open ? 'opacity-0' : ''}`} />
      <nav id="more-menu" aria-label="More" onMouseEnter={show} onMouseLeave={hideSoon}
        onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) hideSoon() }}
        onFocus={show}
        className={`fixed bottom-5 left-1/2 z-50 -translate-x-1/2 rounded-3xl border border-[var(--line)] bg-[var(--glass)] p-2 shadow-[var(--shadow-lift)] backdrop-blur-xl transition duration-300 ease-out ${
          open ? 'translate-y-0 opacity-100' : 'pointer-events-none translate-y-[calc(100%+32px)] opacity-0 focus-within:pointer-events-auto focus-within:translate-y-0 focus-within:opacity-100'
        }`}>
        <ul className="flex items-center gap-1">
          {ITEMS.map(({ to, label, icon: Icon }) => (
            <li key={to}>
              <Link to={to} aria-current={pathname.startsWith(to) ? 'page' : undefined}
                className={`flex flex-col items-center gap-1 rounded-2xl px-4 py-2.5 text-xs font-medium transition hover:bg-[var(--accent-soft)] hover:text-[var(--accent)] ${
                  pathname.startsWith(to) ? 'bg-[var(--accent-soft)] text-[var(--accent)]' : 'text-[var(--ink)]'
                }`}>
                <Icon width={22} height={22} />
                {label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </>
  )
}
