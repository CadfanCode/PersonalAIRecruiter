import { NavLink, Outlet } from 'react-router-dom'

const nav = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/runs', label: 'Runs' },
  { to: '/documents', label: 'Documents' },
  { to: '/apply', label: 'Apply monitor' },
  { to: '/cv', label: 'CV database' },
  { to: '/settings', label: 'Settings' },
]

export function Layout() {
  return (
    <div className="flex min-h-screen">
      <aside className="w-56 shrink-0 border-r border-[var(--line)] bg-[var(--panel)] px-3 py-5">
        <div className="mb-6 px-3 text-lg font-semibold tracking-tight text-[var(--accent)]">JobPilot</div>
        <nav aria-label="Main" className="flex flex-col gap-1">
          {nav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-navy text-white'
                    : 'text-[var(--muted)] hover:bg-[var(--surface)] hover:text-[var(--ink)]'
                }`
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="min-w-0 flex-1 px-8 py-6">
        <Outlet />
      </main>
    </div>
  )
}
