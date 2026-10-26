import { Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { Dashboard } from './pages/Dashboard'
import { Placeholder } from './pages/Placeholder'

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="runs" element={<Placeholder title="Runs" milestone={3} description="Run view and results table." />} />
        <Route path="documents" element={<Placeholder title="Documents" milestone={4} description="Tailored CV and letter editor." />} />
        <Route path="apply" element={<Placeholder title="Apply monitor" milestone={5} description="One card per open application tab." />} />
        <Route path="cv" element={<Placeholder title="CV database" milestone={2} description="Timeline of entries and facts, with editor and import." />} />
        <Route path="settings" element={<Placeholder title="Settings" milestone={2} description="Claude CLI path, model, style rules and banned phrases." />} />
        <Route path="*" element={<Placeholder title="Not found" milestone={1} description="No such page." />} />
      </Route>
    </Routes>
  )
}
