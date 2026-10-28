import { useState } from 'react'
import { type BannedPhrase, type StyleRule, useBannedPhrases, usePhraseActions, useSaveRule, useStyleRules } from '../api/settings'
import { Button, Field, TextArea, TextInput } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'
import { AppearancePanel } from '../components/settings/AppearancePanel'
import { ConnectionPanel } from '../components/settings/ConnectionPanel'
import { LoginsPanel } from '../components/settings/LoginsPanel'
import { PerformancePanel } from '../components/settings/PerformancePanel'
import { SearchPanel } from '../components/settings/SearchPanel'
import { SetupPanel } from '../components/settings/SetupPanel'

const SECTIONS = [
  ['connection', 'AI'],
  ['search', 'Search'],
  ['performance', 'Speed and usage'],
  ['logins', 'Saved logins'],
  ['writing', 'Writing rules'],
  ['appearance', 'Appearance'],
  ['setup', 'Setup'],
] as const

type Section = (typeof SECTIONS)[number][0]

export function SettingsPage() {
  const [section, setSection] = useState<Section>('connection')
  return (
    <>
      <PageTitle>Settings</PageTitle>
      <div className="grid gap-8 md:grid-cols-[13rem_1fr]">
        <nav aria-label="Settings sections" className="flex gap-1 overflow-x-auto md:flex-col">
          {SECTIONS.map(([id, label]) => (
            <button key={id} type="button" aria-current={section === id ? 'page' : undefined} onClick={() => setSection(id)}
              className={`whitespace-nowrap rounded-xl px-3.5 py-2 text-left text-sm font-medium transition ${section === id ? 'bg-[var(--accent-soft)] text-[var(--accent)]' : 'text-[var(--muted)] hover:bg-[var(--line)]/50 hover:text-[var(--ink)]'}`}>
              {label}
            </button>
          ))}
        </nav>
        <div className="min-w-0 max-w-3xl">
          {section === 'connection' && <ConnectionPanel />}
          {section === 'search' && <SearchPanel />}
          {section === 'performance' && <PerformancePanel />}
          {section === 'logins' && <LoginsPanel />}
          {section === 'writing' && <div className="grid gap-5"><RulesPanel /><PhrasesPanel /></div>}
          {section === 'appearance' && <AppearancePanel />}
          {section === 'setup' && <SetupPanel />}
        </div>
      </div>
    </>
  )
}

function RulesPanel() {
  const rules = useStyleRules()
  return (
    <Panel title="Writing rules">
      <p className="mb-3 text-xs text-[var(--muted)]">
        These texts are given to the AI verbatim when it writes a CV or letter. The checks for dashes, new-graduate wording and fact
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
        {save.isError && <span role="alert" className="text-sm text-[var(--danger)]">{save.error.message}</span>}
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
            className="rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2.5 py-1.5 text-sm" aria-label="Phrase language">
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
      {add.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{add.error.message}</p>}
    </Panel>
  )
}
