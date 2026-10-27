import type { Violation } from '../../api/documents'

export function IssueList({ issues }: { issues: Violation[] | undefined }) {
  if (!issues || issues.length === 0) return null
  return (
    <ul className="mt-1 flex flex-col gap-0.5">
      {issues.map((v, i) => (
        <li key={i} className={`text-xs ${v.severity === 'FAIL' ? 'text-red-600' : 'text-amber-600'}`}>
          {v.severity === 'FAIL' ? 'Must fix: ' : 'Check: '}
          {v.message}
        </li>
      ))}
    </ul>
  )
}
