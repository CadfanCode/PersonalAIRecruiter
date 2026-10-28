import type { Report } from '../../api/documents'
import { Panel } from '../Panel'

export function ReportPanel({ report }: { report: Report | null }) {
  const violations = report?.violations ?? []
  const fails = violations.filter((v) => v.severity === 'FAIL').length
  return (
    <Panel title="Validator report">
      {report && (
        <p className="mb-2 text-sm text-[var(--muted)]">
          {report.pages} of {report.maxPages} page{report.maxPages > 1 ? 's' : ''} used
        </p>
      )}
      {violations.length === 0 ? (
        <p role="status" className="text-sm text-[var(--success)]">No problems found.</p>
      ) : (
        <>
          <p role="status" className={`mb-2 text-sm ${fails > 0 ? 'text-[var(--danger)]' : 'text-[var(--warn)]'}`}>
            {fails > 0 ? `${fails} must be fixed before approval` : 'Only warnings: approval is possible'}
          </p>
          <ul className="flex flex-col gap-1.5 text-sm">
            {violations.map((v, i) => (
              <li key={i}>
                <span className={`mr-2 rounded px-1.5 py-0.5 text-xs font-semibold ${v.severity === 'FAIL' ? 'bg-[var(--danger-soft)] text-[var(--danger)]' : 'bg-[var(--warn-soft)] text-[var(--warn)]'}`}>
                  {v.severity}
                </span>
                {v.message}
                {v.text && <code className="ml-1 text-xs text-[var(--muted)]">“{v.text}”</code>}
                <span className="ml-1 text-xs text-[var(--muted)]">({v.where})</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </Panel>
  )
}
