import { NavLink, Outlet } from 'react-router-dom'

interface NavItem {
  to: string
  label: string
  end?: boolean
  step?: number
}

// Three groups, separated by a divider: the guided pipeline, the record of what was applied for, and what the user maintains.
const groups: { label: string; items: NavItem[] }[] = [
  {
    label: 'Pipeline',
    items: [
      { to: '/', label: 'New run', end: true, step: 1 },
      { to: '/runs', label: 'Results', step: 2 },
      { to: '/documents', label: 'Documents', step: 3 },
      { to: '/finalize', label: 'Finalize Applications', step: 4 },
    ],
  },
  { label: 'Record', items: [{ to: '/aktivitet', label: 'Activity Report' }] },
  {
    label: 'Maintain',
    items: [
      { to: '/cv', label: 'CV database' },
      { to: '/settings', label: 'Settings' },
    ],
  },
]

export function Layout() {
  return (
    <div className="flex min-h-screen">
      <aside className="w-60 shrink-0 border-r border-[var(--line)] bg-[var(--panel)] px-3 py-5">
        <div className="mb-6 px-3 text-lg font-semibold tracking-tight text-[var(--accent)]">JobPilot</div>
        <nav aria-label="Main" className="flex flex-col">
          {groups.map((group, i) => (
            <div key={group.label} role="group" aria-label={group.label}
              className={`flex flex-col gap-1 ${i > 0 ? 'mt-4 border-t border-[var(--line)] pt-4' : ''}`}>
              {i === 0 && <div className="mb-1 px-3 text-[11px] font-semibold uppercase tracking-wider text-[var(--muted)]">Pipeline</div>}
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    `flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                      isActive
                        ? 'bg-navy text-white'
                        : 'text-[var(--muted)] hover:bg-[var(--surface)] hover:text-[var(--ink)]'
                    }`
                  }
                >
                  {item.step && <span aria-hidden className="w-4 text-center text-xs opacity-70">{item.step}</span>}
                  {item.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
      </aside>
      <main className="min-w-0 flex-1 px-8 py-6">
        <Outlet />
      </main>
    </div>
  )
}
