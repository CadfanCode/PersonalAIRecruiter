import { Outlet, useLocation } from 'react-router-dom'
import { useReadyNotifications } from './apply/notifications'
import { BottomDock } from './shell/BottomDock'
import { FlowBar } from './shell/FlowBar'

/** The app shell: the flow across the top, the page, and the menu that slides up from the bottom edge. */
export function Layout() {
  const { pathname } = useLocation()
  useReadyNotifications()
  // The results table has many columns and a document needs a large preview, so these pages use the full window width.
  const wide = /^\/(runs\/\d+|documents\/job\/)/.test(pathname)
  return (
    <div className="min-h-screen">
      <FlowBar />
      <main className={`mx-auto pb-28 pt-8 ${wide ? 'max-w-[1800px] px-4' : 'max-w-6xl px-6'}`}>
        <div key={pathname} className="animate-rise">
          <Outlet />
        </div>
      </main>
      <BottomDock />
    </div>
  )
}
