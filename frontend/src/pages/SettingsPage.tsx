import { useState } from 'react'
import { useClaudeUsage } from '../api/claude'
import { type BannedPhrase, type StyleRule, useBannedPhrases, usePhraseActions, useSaveRule, useStyleRules } from '../api/settings'
import { Button, Field, TextArea, TextInput } from '../components/Form'
import { LimitMeters } from '../components/LimitMeters'
import { PageTitle, Panel } from '../components/Panel'

export function SettingsPage() {
  return (
    <>
      <PageTitle>Settings</PageTitle>
      <div className="grid max-w-4xl gap-5">
        <UsagePanel />
        <RulesPanel />
        <PhrasesPanel />
        <Panel title="Claude CLI">
          <p className="text-sm text-[var(--muted)]">
            The CLI path and model are set with environment variables when the API starts:{' '}
            <code>JOBPILOT_CLAUDE_PATH</code> and <code>JOBPILOT_CLAUDE_MODEL</code> (for example <code>sonnet</code>). Without a model,
            your CLI default is used, which can cost several times more per call. Editing them here is planned.
          </p>
        </Panel>
      </div>
    </>
  )
}

function UsagePanel() {
  const usage = useClaudeUsage()
  return (
    <Panel title="Claude usage">
      {usage.isError && <p role="alert" className="text-sm text-red-600">Cannot reach the JobPilot API.</p>}
      <div className="mb-4">
        <LimitMeters />
      </div>
      {usage.data && (
        <>
          <p className="mb-3 text-sm">
            JobPilot has made {usage.data.totals.calls} calls, {usage.data.totals.failures} failed.
          </p>
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase text-[var(--muted)]">
              <tr><th className="py-1 font-semibold">Task</th><th className="py-1 font-semibold">Calls</th><th className="py-1 font-semibold">Tokens</th></tr>
            </thead>
            <tbody>
              {usage.data.byTask.map((t) => (
                <tr key={t.task} className="border-t border-[var(--line)]">
                  <td className="py-1">{t.task}</td><td>{t.calls}</td><td>{Number(t.tokens).toLocaleString('en-GB')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </Panel>
  )
}

function RulesPanel() {
  const rules = useStyleRules()
  return (
    <Panel title="Writing rules">
      <p className="mb-3 text-xs text-[var(--muted)]">
        These texts are given to Claude verbatim when it writes a CV or letter. The checks for dashes, new-graduate wording and fact
        citations always run, whatever is written here.
      </p>
      <div className="flex flex-col gap-4">
        {rules.data?.map((r) => <RuleEditor key={`${r.id}-${r.ruleText}-${r.enabled}`} rule={r} />)}
      </div>
    </Panel>
  )
}

function RuleEditor({ rule }: { rule: StyleRule }) {
  const [text, setText] = useState(rule.ruleText)
  const [enabled, setEnabled] = useState(rule.enabled)
  const save = useSaveRule()
  const dirty = text !== rule.ruleText || enabled !== rule.enabled
  return (
    <div>
      <label className="mb-1 flex items-center gap-2 text-xs font-medium text-[var(--muted)]">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} aria-label={`Enable ${rule.ruleKey}`} />
        {rule.ruleKey}
      </label>
      <TextArea aria-label={`Rule ${rule.ruleKey}`} rows={2} value={text} onChange={(e) => setText(e.target.value)} />
      <div className="mt-1 flex items-center gap-3">
        <Button variant="ghost" disabled={!dirty || save.isPending || text.trim() === ''}
          onClick={() => save.mutate({ id: rule.id, ruleText: text, enabled })}>Save rule</Button>
        {save.isError && <span role="alert" className="text-sm text-red-600">{save.error.message}</span>}
      </div>
    </div>
  )
}

function PhrasesPanel() {
  const phrases = useBannedPhrases()
  const { add, toggle, remove } = usePhraseActions()
  const [language, setLanguage] = useState<'en' | 'sv'>('sv')
  const [phrase, setPhrase] = useState('')
  const group = (lang: 'en' | 'sv') => (phrases.data ?? []).filter((p) => p.language === lang)

  return (
    <Panel title="Banned phrases">
      <p className="mb-3 text-xs text-[var(--muted)]">
        Typical AI phrasing. A match is flagged and triggers one automatic rewrite; it never blocks approval. Write "a ... b" to match
        two parts in one sentence.
      </p>
      {(['sv', 'en'] as const).map((lang) => (
        <div key={lang} className="mb-3">
          <h3 className="mb-1 text-xs font-semibold uppercase text-[var(--muted)]">{lang === 'sv' ? 'Swedish' : 'English'}</h3>
          <ul className="flex flex-wrap gap-1.5">
            {group(lang).map((p: BannedPhrase) => (
              <li key={p.id} className={`flex items-center gap-1.5 rounded-full border border-[var(--line)] px-2.5 py-0.5 text-sm ${p.enabled ? '' : 'text-[var(--muted)] line-through'}`}>
                <input type="checkbox" checked={p.enabled} aria-label={`Enable phrase ${p.phrase}`}
                  onChange={(e) => toggle.mutate({ id: p.id, enabled: e.target.checked })} />
                {p.phrase}
                <button type="button" aria-label={`Delete phrase ${p.phrase}`} onClick={() => remove.mutate(p.id)}>×</button>
              </li>
            ))}
          </ul>
        </div>
      ))}
      <div className="mt-3 flex items-end gap-2">
        <Field label="Language">
          <select value={language} onChange={(e) => setLanguage(e.target.value as 'en' | 'sv')}
            className="rounded-md border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1.5 text-sm" aria-label="Phrase language">
            <option value="sv">Swedish</option>
            <option value="en">English</option>
          </select>
        </Field>
        <Field label="Phrase">
          <TextInput value={phrase} onChange={(e) => setPhrase(e.target.value)} />
        </Field>
        <Button disabled={!phrase.trim() || add.isPending}
          onClick={() => add.mutate({ language, phrase: phrase.trim() }, { onSuccess: () => setPhrase('') })}>Add phrase</Button>
      </div>
      {add.isError && <p role="alert" className="mt-2 text-sm text-red-600">{add.error.message}</p>}
    </Panel>
  )
}
