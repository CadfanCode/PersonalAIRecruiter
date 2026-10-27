import type { FactView, LetterContent } from '../../api/documents'
import { Field, TextArea, TextInput } from '../Form'
import { FactChips } from './FactChips'
import { IssueList } from './Issues'
import { type IssueMap, issueClass } from './issues'

export function LetterEditor({
  content,
  facts,
  issues,
  onChange,
}: {
  content: LetterContent
  facts: Record<string, FactView>
  issues: IssueMap
  onChange: (c: LetterContent) => void
}) {
  const set = (patch: Partial<LetterContent>) => onChange({ ...content, ...patch })
  const line = (label: string, key: 'subject' | 'subline' | 'greeting' | 'closing' | 'signature') => (
    <Field label={label}>
      <TextInput className={issueClass(issues[key])} value={content[key]} onChange={(e) => set({ [key]: e.target.value })} />
      <IssueList issues={issues[key]} />
    </Field>
  )
  return (
    <div className="flex flex-col gap-3">
      {line('Subject', 'subject')}
      {line('Employer line', 'subline')}
      {line('Greeting', 'greeting')}
      {content.paragraphs.map((p, i) => (
        <div key={i} data-where={`paragraphs[${i}]`}>
          <TextArea
            aria-label={`Paragraph ${i + 1}`}
            rows={6}
            className={issueClass(issues[`paragraphs[${i}]`])}
            value={p}
            onChange={(e) => set({ paragraphs: content.paragraphs.map((x, j) => (j === i ? e.target.value : x)) })}
          />
          <IssueList issues={issues[`paragraphs[${i}]`]} />
        </div>
      ))}
      {line('Closing', 'closing')}
      {line('Signature', 'signature')}
      <div>
        <span className="mr-2 text-xs text-[var(--muted)]">Facts used:</span>
        <FactChips ids={content.factIdsUsed} facts={facts} language={content.language} />
        <IssueList issues={issues['factIdsUsed']} />
      </div>
    </div>
  )
}
