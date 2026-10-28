import { Outlet, useLocation } from 'react-router-dom'
import { useReadyNotifications } from './apply/notifications'
import { useImportWatcher } from './cv/useImportWatcher'
import { Tour } from './Tour'
import { BottomDock } from './shell/BottomDock'
import { FlowBar } from './shell/FlowBar'

/**
 * The app shell, around every screen including setup: the bar across the top (with the theme switch), the page, and the menu that
 * slides up from the bottom edge. Pages are swapped inside it; the shell itself never remounts.
 */
export function Layout() {
  const { pathname } = useLocation()
  useReadyNotifications()
  useImportWatcher()
  // The results table has many columns and a document needs a large preview, so these pages use the full window width.
  const wide = /^\/(runs\/\d+|documents\/job\/)/.test(pathname)
  return (
    <div className="min-h-screen">
      <FlowBar />
      <main className={`mx-auto pb-28 pt-8 ${wide ? 'max-w-[1800px] px-5' : 'max-w-6xl px-6'}`}>
        <div key={pathname} className="animate-rise">
          <Outlet />
        </div>
      </main>
      <BottomDock />
      <Tour />
    </div>
  )
}
