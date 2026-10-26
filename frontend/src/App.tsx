import { Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { CvDatabase } from './pages/CvDatabase'
import { Dashboard } from './pages/Dashboard'
import { Placeholder } from './pages/Placeholder'
import { RunPage } from './pages/RunPage'
import { RunsPage } from './pages/RunsPage'

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="runs" element={<RunsPage />} />
        <Route path="runs/:id" element={<RunPage />} />
        <Route path="documents" element={<Placeholder title="Documents" milestone={4} description="Tailored CV and letter editor." />} />
        <Route path="apply" element={<Placeholder title="Apply monitor" milestone={5} description="One card per open application tab." />} />
        <Route path="cv" element={<CvDatabase />} />
        <Route path="settings" element={<Placeholder title="Settings" milestone={4} description="Claude CLI path, model, style rules and banned phrases." />} />
        <Route path="*" element={<Placeholder title="Not found" milestone={1} description="No such page." />} />
      </Route>
    </Routes>
  )
}
