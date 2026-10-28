import { Outlet, useLocation } from 'react-router-dom'
import { BottomDock } from './shell/BottomDock'
import { FlowBar } from './shell/FlowBar'

/** The app shell: the flow across the top, the page, and the menu that slides up from the bottom edge. */
export function Layout() {
  const { pathname } = useLocation()
  return (
    <div className="min-h-screen">
      <FlowBar />
      <main className="mx-auto max-w-6xl px-6 pb-28 pt-8">
        <div key={pathname} className="animate-rise">
          <Outlet />
        </div>
      </main>
      <BottomDock />
    </div>
  )
}
