import { Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { ApplyMonitorPage } from './pages/ApplyMonitorPage'
import { CvDatabase } from './pages/CvDatabase'
import { Dashboard } from './pages/Dashboard'
import { DocumentsIndexPage } from './pages/DocumentsIndexPage'
import { JobDocumentsPage } from './pages/JobDocumentsPage'
import { Placeholder } from './pages/Placeholder'
import { RunPage } from './pages/RunPage'
import { SettingsPage } from './pages/SettingsPage'
import { RunsPage } from './pages/RunsPage'

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="runs" element={<RunsPage />} />
        <Route path="runs/:id" element={<RunPage />} />
        <Route path="documents" element={<DocumentsIndexPage />} />
        <Route path="documents/job/:jobId" element={<JobDocumentsPage />} />
        <Route path="apply" element={<ApplyMonitorPage />} />
        <Route path="cv" element={<CvDatabase />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="*" element={<Placeholder title="Not found" milestone={1} description="No such page." />} />
      </Route>
    </Routes>
  )
}
