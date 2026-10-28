import { gsap } from 'gsap'
import { useLayoutEffect, useRef } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { useReadyNotifications } from './apply/notifications'
import { useImportWatcher } from './cv/useImportWatcher'
import { Tour } from './Tour'
import { BottomDock } from './shell/BottomDock'
import { FlowBar } from './shell/FlowBar'

/**
 * Each screen arrives as a short staggered rise: its top-level blocks fade and lift in one after another. Skipped when the system asks
 * for less motion (and where matchMedia does not exist, as in the test environment); `clearProps` leaves no inline styles behind.
 */
function useStagger(page: React.RefObject<HTMLDivElement | null>, key: string) {
  useLayoutEffect(() => {
    const el = page.current
    if (!el || typeof window.matchMedia !== 'function' || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const tween = gsap.fromTo(Array.from(el.children), { opacity: 0, y: 12 }, { opacity: 1, y: 0, duration: 0.5, stagger: 0.055, ease: 'power3.out', clearProps: 'opacity,transform' })
    return () => { tween.kill(); gsap.set(Array.from(el.children), { clearProps: 'opacity,transform' }) }
  }, [page, key])
}

/**
 * The app shell, around every screen including setup: the bar across the top (with the theme switch), the page, and the menu that
 * slides up from the bottom edge. Pages are swapped inside it; the shell itself never remounts.
 */
export function Layout() {
  const { pathname } = useLocation()
  useReadyNotifications()
  useImportWatcher()
  // The results table has many columns and a document needs a large preview, so these pages use the full window width.
  const page = useRef<HTMLDivElement>(null)
  useStagger(page, pathname)
  const wide = /^\/(runs\/\d+|documents\/job\/)/.test(pathname)
  return (
    <div className="min-h-screen">
      <FlowBar />
      <main className={`mx-auto pb-28 pt-10 ${wide ? 'max-w-[1800px] px-5' : 'max-w-6xl px-6'}`}>
        <div key={pathname} ref={page}>
          <Outlet />
        </div>
      </main>
      <BottomDock />
      <Tour />
    </div>
  )
}
