import type { Violation } from '../../api/documents'

export type IssueMap = Record<string, Violation[]>

export function groupIssues(violations: Violation[] | undefined): IssueMap {
  const map: IssueMap = {}
  for (const v of violations ?? []) (map[v.where] ??= []).push(v)
  return map
}

/** Outline colour for a field: red when it has a FAIL, amber for WARN only. */
export function issueClass(issues: Violation[] | undefined): string {
  if (!issues || issues.length === 0) return ''
  return issues.some((v) => v.severity === 'FAIL') ? 'ring-2 ring-red-500' : 'ring-2 ring-amber-400'
}
