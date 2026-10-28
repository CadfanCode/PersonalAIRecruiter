import type { ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useOnboarding } from './api/onboarding'
import { Layout } from './components/Layout'
import { AktivitetPage } from './pages/AktivitetPage'
import { ApplyPage } from './pages/ApplyPage'
import { CvDatabase } from './pages/CvDatabase'
import { Dashboard } from './pages/Dashboard'
import { DocumentsIndexPage } from './pages/DocumentsIndexPage'
import { ExtrasPage } from './pages/ExtrasPage'
import { JobDocumentsPage } from './pages/JobDocumentsPage'
import { OnboardingPage } from './pages/OnboardingPage'
import { Placeholder } from './pages/Placeholder'
import { RunPage } from './pages/RunPage'
import { RunsPage } from './pages/RunsPage'
import { SettingsPage } from './pages/SettingsPage'
import { SubmitPage } from './pages/SubmitPage'
import { SubmittedDetailPage } from './pages/SubmittedDetailPage'
import { SubmittedPage } from './pages/SubmittedPage'

/** First launch goes through the welcome steps; once they are done the app never shows them again unless asked to. */
function RequireSetup({ children }: { children: ReactNode }) {
  const onboarding = useOnboarding()
  const location = useLocation()
  if (onboarding.isPending) {
    return <div className="flex min-h-screen items-center justify-center text-sm text-[var(--muted)]">Getting JobPilot ready…</div>
  }
  // If the API cannot be reached the pages themselves say so; setup is only forced when it is known to be unfinished.
  if (onboarding.data && !onboarding.data.complete && location.pathname !== '/welcome') return <Navigate to="/welcome" replace />
  return <>{children}</>
}

export function App() {
  return (
    <Routes>
      <Route path="welcome" element={<OnboardingPage />} />
      <Route element={<RequireSetup><Layout /></RequireSetup>}>
        <Route index element={<Dashboard />} />
        <Route path="runs" element={<RunsPage />} />
        <Route path="runs/:id" element={<RunPage />} />
        <Route path="documents" element={<DocumentsIndexPage />} />
        <Route path="documents/job/:jobId" element={<JobDocumentsPage />} />
        <Route path="apply" element={<ApplyPage />} />
        <Route path="submit" element={<SubmitPage />} />
        <Route path="finalize" element={<Navigate to="/apply" replace />} />
        <Route path="submitted" element={<SubmittedPage />} />
        <Route path="submitted/:id" element={<SubmittedDetailPage />} />
        <Route path="extras" element={<ExtrasPage />} />
        <Route path="aktivitet" element={<AktivitetPage />} />
        <Route path="cv" element={<CvDatabase />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="*" element={<Placeholder title="Not found" milestone={1} description="No such page." />} />
      </Route>
    </Routes>
  )
}
