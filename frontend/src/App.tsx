import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useOnboarding } from './api/onboarding'
import { Layout } from './components/Layout'
import { AktivitetPage } from './pages/AktivitetPage'
import { ApplyPage } from './pages/ApplyPage'
import { CvDatabase } from './pages/CvDatabase'
import { Dashboard } from './pages/Dashboard'
import { DocumentsIndexPage } from './pages/DocumentsIndexPage'
import { ExtrasPage } from './pages/ExtrasPage'
import { HelpPage } from './pages/HelpPage'
import { JobDocumentsPage } from './pages/JobDocumentsPage'
import { NotStartedPage } from './pages/NotStartedPage'
import { OnboardingPage } from './pages/OnboardingPage'
import { Placeholder } from './pages/Placeholder'
import { RunPage } from './pages/RunPage'
import { RunsPage } from './pages/RunsPage'
import { SettingsPage } from './pages/SettingsPage'
import { SetupProvider } from './setup/SetupProvider'
import { SubmitPage } from './pages/SubmitPage'
import { SubmittedDetailPage } from './pages/SubmittedDetailPage'
import { SubmittedPage } from './pages/SubmittedPage'

/** The search pipeline needs a finished setup; everything else (CV database, Settings, the bottom menu) works at any time. */
function RequireSetup({ children }: { children: ReactNode }) {
  const onboarding = useOnboarding()
  if (onboarding.isPending) {
    return <div className="flex min-h-[40vh] items-center justify-center text-sm text-[var(--muted)]">Getting JobPilot ready…</div>
  }
  // If the API cannot be reached the pages themselves say so; setup is only forced when it is known to be unfinished.
  if (onboarding.data && !onboarding.data.complete) return <Navigate to="/welcome" replace />
  return <>{children}</>
}

const gated = (page: ReactNode) => <RequireSetup>{page}</RequireSetup>

export function App() {
  return (
    <SetupProvider>
      <Routes>
        <Route element={<Layout />}>
          <Route path="welcome" element={<OnboardingPage />} />
          <Route index element={gated(<Dashboard />)} />
          <Route path="runs" element={gated(<RunsPage />)} />
          <Route path="runs/:id" element={gated(<RunPage />)} />
          <Route path="documents" element={gated(<DocumentsIndexPage />)} />
          <Route path="documents/job/:jobId" element={gated(<JobDocumentsPage />)} />
          <Route path="apply" element={gated(<ApplyPage />)} />
          <Route path="submit" element={gated(<SubmitPage />)} />
          <Route path="not-started/:stage" element={gated(<NotStartedPage />)} />
          <Route path="finalize" element={<Navigate to="/apply" replace />} />
          <Route path="submitted" element={<SubmittedPage />} />
          <Route path="submitted/:id" element={<SubmittedDetailPage />} />
          <Route path="extras" element={<ExtrasPage />} />
          <Route path="aktivitet" element={<AktivitetPage />} />
          <Route path="cv" element={<CvDatabase />} />
          <Route path="help" element={<HelpPage />} />
          <Route path="settings/:section?" element={<SettingsPage />} />
          <Route path="*" element={<Placeholder title="Not found" milestone={1} description="No such page." />} />
        </Route>
      </Routes>
    </SetupProvider>
  )
}
