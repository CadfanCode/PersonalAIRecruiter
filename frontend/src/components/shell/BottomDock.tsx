import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ArchiveIcon, ChevronUpIcon, GlobeIcon, GridIcon, SlidersIcon, SparkIcon, UserIcon } from '../ui/Icons'

const ITEMS = [
  { to: '/cv', label: 'CV database', icon: UserIcon, active: (p: string) => p.startsWith('/cv') },
  { to: '/settings/country', label: 'Country', icon: GlobeIcon, active: (p: string) => p === '/settings/country' },
  { to: '/settings/connection', label: 'Claude', icon: SparkIcon, active: (p: string) => p === '/settings/connection' },
  { to: '/submitted', label: 'Submitted applications', icon: ArchiveIcon, active: (p: string) => p.startsWith('/submitted') },
  { to: '/extras', label: 'Extra features', icon: GridIcon, active: (p: string) => p.startsWith('/extras') },
  { to: '/settings', label: 'Settings', icon: SlidersIcon, active: (p: string) => p.startsWith('/settings') && p !== '/settings/country' && p !== '/settings/connection' },
] as const

/**
 * The menu that is always one move away, on every screen including setup. It slides up when the pointer goes to the bottom edge, when
 * the handle is clicked or focused, or when Tab reaches one of its links; it never depends on hover alone. Choosing an item only
 * changes the page: searches, documents and applications keep running, and the work held in the browser is kept.
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
      <div aria-hidden className="fixed inset-x-0 bottom-0 z-40 h-3" onMouseEnter={show} />
      <button type="button" aria-label="More: CV database, country, Claude, submitted applications, extra features, settings" aria-expanded={open} aria-controls="more-menu"
        onClick={show} onMouseEnter={show} onFocus={show}
        className={`fixed bottom-0 left-1/2 z-40 flex h-6 -translate-x-1/2 cursor-pointer items-center gap-1 rounded-t-md border border-b-0 border-[var(--line)] bg-[var(--panel)] px-3 text-[11px] font-medium text-[var(--muted)] transition hover:text-[var(--ink)] ${open ? 'opacity-0' : ''}`}>
        <ChevronUpIcon width={12} height={12} /> Menu
      </button>
      <nav id="more-menu" aria-label="More" onMouseEnter={show} onMouseLeave={hideSoon}
        onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) hideSoon() }}
        onFocus={show}
        className={`fixed bottom-4 left-1/2 z-50 max-w-[calc(100vw-1rem)] -translate-x-1/2 overflow-x-auto rounded-xl border border-[var(--line)] bg-[var(--panel)] p-1.5 shadow-[var(--shadow-lift)] transition duration-200 ease-out ${
          open ? 'translate-y-0 opacity-100' : 'pointer-events-none translate-y-[calc(100%+32px)] opacity-0 focus-within:pointer-events-auto focus-within:translate-y-0 focus-within:opacity-100'
        }`}>
        <ul className="flex items-center gap-0.5">
          {ITEMS.map(({ to, label, icon: Icon, active }) => (
            <li key={to}>
              <Link to={to} aria-current={active(pathname) ? 'page' : undefined}
                className={`flex min-w-[5.5rem] cursor-pointer flex-col items-center gap-1 rounded-lg px-3 py-2 text-center text-xs font-medium transition-colors hover:bg-[var(--hover)] ${
                  active(pathname) ? 'bg-[var(--accent-soft)] text-[var(--accent)]' : 'text-[var(--ink)]'
                }`}>
                <Icon width={20} height={20} />
                {label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </>
  )
}
