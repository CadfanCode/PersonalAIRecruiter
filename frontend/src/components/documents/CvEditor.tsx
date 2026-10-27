import type { CvContent, FactView } from '../../api/documents'
import { Field, TextArea, TextInput } from '../Form'
import { FactChips } from './FactChips'
import { IssueList } from './Issues'
import { type IssueMap, issueClass } from './issues'

/** Paragraph-level editing of a generated CV (SPEC 8.5). Fields under a section item come from the CV database. */
export function CvEditor({
  content,
  facts,
  issues,
  onChange,
}: {
  content: CvContent
  facts: Record<string, FactView>
  issues: IssueMap
  onChange: (c: CvContent) => void
}) {
  const set = (patch: Partial<CvContent>) => onChange({ ...content, ...patch })
  const setItem = (s: number, i: number, patch: Partial<CvContent['sections'][number]['items'][number]>) =>
    set({
      sections: content.sections.map((sec, si) =>
        si !== s ? sec : { ...sec, items: sec.items.map((it, ii) => (ii !== i ? it : { ...it, ...patch })) },
      ),
    })

  return (
    <div className="flex flex-col gap-4">
      <Field label="Headline">
        <TextInput className={issueClass(issues['headline'])} value={content.headline} onChange={(e) => set({ headline: e.target.value })} />
        <IssueList issues={issues['headline']} />
      </Field>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-xs font-medium text-[var(--muted)]">Profile</legend>
        {content.profileParagraphs.map((p, i) => (
          <div key={i}>
            <TextArea
              aria-label={`Profile paragraph ${i + 1}`}
              rows={3}
              className={issueClass(issues[`profileParagraphs[${i}]`])}
              value={p}
              onChange={(e) => set({ profileParagraphs: content.profileParagraphs.map((x, j) => (j === i ? e.target.value : x)) })}
            />
            <IssueList issues={issues[`profileParagraphs[${i}]`]} />
          </div>
        ))}
      </fieldset>

      {content.sections.map((section, s) => (
        <section key={s} aria-label={section.heading} className="rounded-md border border-[var(--line)] p-3">
          <TextInput
            aria-label={`Heading of section ${s + 1}`}
            className={`mb-3 font-semibold ${issueClass(issues[`sections[${s}].heading`])}`}
            value={section.heading}
            onChange={(e) =>
              set({ sections: content.sections.map((sec, si) => (si === s ? { ...sec, heading: e.target.value } : sec)) })
            }
          />
          <IssueList issues={issues[`sections[${s}].heading`]} />
          {section.items.map((item, i) => {
            const base = `sections[${s}].items[${i}]`
            return (
              <div key={i} className="mb-4 last:mb-0">
                {section.kind === 'SKILLS' ? (
                  <div className="grid grid-cols-[10rem_1fr] gap-2">
                    <TextInput aria-label="Skill group" value={item.title} onChange={(e) => setItem(s, i, { title: e.target.value })} />
                    <div>
                      <TextInput
                        aria-label="Skills"
                        className={issueClass(issues[`${base}.meta`])}
                        value={item.meta ?? ''}
                        onChange={(e) => setItem(s, i, { meta: e.target.value })}
                      />
                      <IssueList issues={issues[`${base}.meta`]} />
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="grid grid-cols-4 gap-2">
                      <TextInput aria-label="Title" className={issueClass(issues[`${base}.title`])} value={item.title} onChange={(e) => setItem(s, i, { title: e.target.value })} />
                      <TextInput aria-label="Organisation" className={issueClass(issues[`${base}.meta`])} value={item.meta ?? ''} onChange={(e) => setItem(s, i, { meta: e.target.value })} />
                      <TextInput aria-label="Place" value={item.location ?? ''} onChange={(e) => setItem(s, i, { location: e.target.value })} />
                      <TextInput aria-label="Dates" className={issueClass(issues[`${base}.dateLabel`])} value={item.dateLabel ?? ''} onChange={(e) => setItem(s, i, { dateLabel: e.target.value })} />
                    </div>
                    <IssueList issues={[...(issues[`${base}.title`] ?? []), ...(issues[`${base}.meta`] ?? []), ...(issues[`${base}.dateLabel`] ?? [])]} />
                    <IssueList issues={issues[base]} />
                  </>
                )}
                {item.bullets.map((b, bi) => {
                  const where = `${base}.bullets[${bi}]`
                  return (
                    <div key={bi} className="mt-2" data-where={where}>
                      <TextArea
                        aria-label={`Bullet ${bi + 1} of ${item.title || section.heading}`}
                        rows={2}
                        className={issueClass(issues[where])}
                        value={b.text}
                        onChange={(e) =>
                          setItem(s, i, { bullets: item.bullets.map((x, xi) => (xi === bi ? { ...x, text: e.target.value } : x)) })
                        }
                      />
                      <div className="mt-1 flex items-center gap-2">
                        <FactChips ids={b.factIds} facts={facts} language={content.language} />
                        <button
                          type="button"
                          className="ml-auto text-xs text-[var(--muted)] underline"
                          onClick={() => setItem(s, i, { bullets: item.bullets.filter((_, xi) => xi !== bi) })}
                        >
                          Remove bullet
                        </button>
                      </div>
                      <IssueList issues={issues[where]} />
                    </div>
                  )
                })}
              </div>
            )
          })}
        </section>
      ))}

      <Field label="Other employment line (from the CV database dates)">
        <TextInput className={issueClass(issues['gapNote'])} value={content.gapNote ?? ''} onChange={(e) => set({ gapNote: e.target.value || null })} />
        <IssueList issues={issues['gapNote']} />
      </Field>
      <Field label="Languages line">
        <TextInput className={issueClass(issues['languagesLine'])} value={content.languagesLine ?? ''} onChange={(e) => set({ languagesLine: e.target.value || null })} />
        <IssueList issues={issues['languagesLine']} />
      </Field>
    </div>
  )
}
