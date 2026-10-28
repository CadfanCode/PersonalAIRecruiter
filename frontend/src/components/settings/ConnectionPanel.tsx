import { useState } from 'react'
import {
  type ProviderId,
  type TestResult,
  CLI_MODELS,
  useConnection,
  useDeleteApiKey,
  useGeminiModels,
  useSaveApiKey,
  useSelectProvider,
  useSetCliModel,
  useSetGeminiModel,
  useTestConnection,
} from '../../api/connection'
import { Button, Field, TextInput } from '../Form'
import { LimitMeters } from '../LimitMeters'
import { Panel } from '../Panel'
import { CheckIcon } from '../ui/Icons'
import { Pill } from '../ui/Pill'

const SELECT = 'rounded-xl border border-[var(--line)] bg-[var(--panel)] px-3 py-2 text-sm'

/** Which AI access JobPilot uses (Claude Code or Google AI Studio), the models, the key (kept in the credential store), and a real test. */
export function ConnectionPanel() {
  const conn = useConnection()
  const select = useSelectProvider()
  const saveKey = useSaveApiKey()
  const deleteKey = useDeleteApiKey()
  const setGeminiModel = useSetGeminiModel()
  const setCliModel = useSetCliModel()
  const test = useTestConnection()
  const [key, setKey] = useState('')
  const [result, setResult] = useState<TestResult | null>(null)
  const s = conn.data
  const geminiModels = useGeminiModels(!!s?.gemini.configured)

  const choose = (id: ProviderId) => {
    setResult(null)
    select.mutate(id)
  }
  const runTest = () => test.mutate(s?.selected ?? 'cli', { onSuccess: setResult })

  return (
    <div className="grid gap-5">
      <Panel title="AI connection">
        {!s ? <p className="text-sm text-[var(--muted)]">Loading…</p> : (
          <>
            <div role="radiogroup" aria-label="AI access" className="inline-flex rounded-full bg-[var(--line)]/60 p-1 text-sm">
              {([['cli', 'Claude Code subscription'], ['gemini', 'Google AI Studio API key']] as const).map(([id, label]) => (
                <button key={id} type="button" role="radio" aria-checked={s.selected === id} onClick={() => choose(id)}
                  disabled={id === 'gemini' && !s.gemini.configured}
                  className={`rounded-full px-4 py-1.5 font-medium transition disabled:opacity-45 ${s.selected === id ? 'bg-[var(--panel)] shadow-sm' : 'text-[var(--muted)]'}`}>
                  {label}
                </button>
              ))}
            </div>
            {!s.gemini.configured && <p className="mt-2 text-xs text-[var(--muted)]">Add a Google AI Studio key below to switch to it.</p>}

            <div className="mt-5 flex flex-wrap items-center gap-3">
              <Button onClick={runTest} disabled={test.isPending}>{test.isPending ? 'Testing…' : 'Test the connection'}</Button>
              {result?.ok && <span role="status" className="flex items-center gap-1.5 text-sm font-medium text-[var(--success)]"><CheckIcon width={16} height={16} /> Connected</span>}
            </div>
            {result && !result.ok && (
              <div role="alert" className="mt-3 rounded-2xl bg-[var(--danger-soft)] px-4 py-3 text-sm text-[var(--danger)]">
                <p className="font-medium">{result.message}</p>
                {result.help && <p className="mt-1 text-[var(--ink)]">{result.help}</p>}
              </div>
            )}
            {s.selected === 'cli' && (
              <>
                <p className="mt-4 text-sm text-[var(--muted)]">
                  {s.cli.found ? <>Found {s.cli.version ?? 'Claude Code'} at <code>{s.cli.path}</code>.</> : <>Claude Code was not found. {s.cli.help}</>}
                </p>
                <div className="mt-4">
                  <Field label="Claude model" hint="Used from the next request on. The aliases follow the newest model of each family.">
                    <select aria-label="Claude model" className={SELECT} value={s.cli.model} disabled={setCliModel.isPending}
                      onChange={(e) => setCliModel.mutate(e.target.value)}>
                      {CLI_MODELS.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
                      {!CLI_MODELS.some((m) => m.id === s.cli.model) && <option value={s.cli.model}>{s.cli.model}</option>}
                    </select>
                  </Field>
                  {setCliModel.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{setCliModel.error.message}</p>}
                </div>
              </>
            )}
          </>
        )}
      </Panel>

      {s && (
        <Panel title="Google AI Studio API key">
          <p className="mb-3 text-sm text-[var(--muted)]">Kept in {s.secureStore.name}. Never in plain text, never in the database or the logs.</p>
          {s.gemini.configured && <p className="mb-3 flex items-center gap-2 text-sm"><Pill tone="success"><CheckIcon width={12} height={12} /> Saved</Pill> {s.gemini.keyHint}</p>}
          <div className="flex flex-wrap items-end gap-3">
            <Field label={s.gemini.configured ? 'Replace the key' : 'API key'}>
              <TextInput type="password" autoComplete="off" spellCheck={false} placeholder="AIza…" aria-label="Google AI Studio API key" className="w-80" value={key} onChange={(e) => setKey(e.target.value)} />
            </Field>
            <Button disabled={key.trim().length < 20 || saveKey.isPending} onClick={() => saveKey.mutate({ key }, { onSuccess: () => setKey('') })}>Save key</Button>
            {s.gemini.configured && <Button variant="danger" disabled={deleteKey.isPending} onClick={() => deleteKey.mutate()}>Remove key</Button>}
          </div>
          {(saveKey.isError || deleteKey.isError) && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{(saveKey.error ?? deleteKey.error)?.message}</p>}
          {s.gemini.configured && (
            <div className="mt-5">
              <Field label="Gemini model">
                <select aria-label="Gemini model" className={SELECT} value={s.gemini.model} disabled={setGeminiModel.isPending}
                  onChange={(e) => setGeminiModel.mutate(e.target.value)}>
                  {(geminiModels.data ?? []).map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
                  {!(geminiModels.data ?? []).some((m) => m.id === s.gemini.model) && <option value={s.gemini.model}>{s.gemini.model}</option>}
                </select>
              </Field>
            </div>
          )}
        </Panel>
      )}

      {s?.selected === 'cli' && (
        <Panel title="Subscription usage"><LimitMeters /></Panel>
      )}
    </div>
  )
}
