import { useState } from 'react'
import {
  type ProviderId,
  type TestResult,
  useConnection,
  useDeleteApiKey,
  useSaveApiKey,
  useSelectProvider,
  useSetApiModel,
  useTestConnection,
} from '../../api/connection'
import { Button, Field, TextInput } from '../Form'
import { LimitMeters } from '../LimitMeters'
import { Panel } from '../Panel'
import { CheckIcon } from '../ui/Icons'
import { Pill } from '../ui/Pill'

const MODELS = ['claude-sonnet-5-5', 'claude-opus-5-5', 'claude-haiku-5-5']

/** Which Claude access JobPilot uses, the API key (kept in the OS credential store), and a real test of the connection. */
export function ConnectionPanel() {
  const conn = useConnection()
  const select = useSelectProvider()
  const saveKey = useSaveApiKey()
  const deleteKey = useDeleteApiKey()
  const setModel = useSetApiModel()
  const test = useTestConnection()
  const [key, setKey] = useState('')
  const [model, setModelText] = useState<string | null>(null)
  const [result, setResult] = useState<TestResult | null>(null)
  const s = conn.data

  const choose = (id: ProviderId) => {
    setResult(null)
    select.mutate(id)
  }
  const runTest = () => test.mutate(s?.selected ?? 'cli', { onSuccess: setResult })

  return (
    <div className="grid gap-5">
      <Panel title="Claude connection">
        {!s ? <p className="text-sm text-[var(--muted)]">Loading…</p> : (
          <>
            <div role="radiogroup" aria-label="Claude access" className="inline-flex rounded-full bg-[var(--line)]/60 p-1 text-sm">
              {([['cli', 'Claude Code subscription'], ['api', 'Anthropic API key']] as const).map(([id, label]) => (
                <button key={id} type="button" role="radio" aria-checked={s.selected === id} onClick={() => choose(id)}
                  disabled={id === 'api' && !s.api.configured}
                  className={`rounded-full px-4 py-1.5 font-medium transition disabled:opacity-45 ${s.selected === id ? 'bg-[var(--panel)] shadow-sm' : 'text-[var(--muted)]'}`}>
                  {label}
                </button>
              ))}
            </div>
            {!s.api.configured && <p className="mt-2 text-xs text-[var(--muted)]">Add an API key below to switch to it.</p>}

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
              <p className="mt-4 text-sm text-[var(--muted)]">
                {s.cli.found ? <>Found {s.cli.version ?? 'Claude Code'} at <code>{s.cli.path}</code>.</> : <>Claude Code was not found. {s.cli.help}</>}
              </p>
            )}
          </>
        )}
      </Panel>

      {s && (
        <Panel title="Anthropic API key">
          {!s.secureStore.available ? (
            <p role="alert" className="text-sm text-[var(--danger)]">No secure credential store is available on this computer, so a key cannot be saved. On Linux, install and unlock a keyring such as GNOME Keyring or KWallet.</p>
          ) : (
            <>
              <p className="mb-3 text-sm text-[var(--muted)]">Kept in your computer's secure store: {s.secureStore.name}. Never in a plain file.</p>
              {s.api.configured && <p className="mb-3 flex items-center gap-2 text-sm"><Pill tone="success"><CheckIcon width={12} height={12} /> Saved</Pill> {s.api.keyHint}</p>}
              <div className="flex flex-wrap items-end gap-3">
                <Field label={s.api.configured ? 'Replace the key' : 'API key'}>
                  <TextInput type="password" autoComplete="off" spellCheck={false} placeholder="sk-ant-…" aria-label="Anthropic API key" className="w-80" value={key} onChange={(e) => setKey(e.target.value)} />
                </Field>
                <Button disabled={key.trim().length < 20 || saveKey.isPending} onClick={() => saveKey.mutate({ key }, { onSuccess: () => setKey('') })}>Save key</Button>
                {s.api.configured && <Button variant="danger" disabled={deleteKey.isPending} onClick={() => deleteKey.mutate()}>Remove key</Button>}
              </div>
              {(saveKey.isError || deleteKey.isError) && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{(saveKey.error ?? deleteKey.error)?.message}</p>}
              <div className="mt-5 flex flex-wrap items-end gap-3">
                <Field label="Model used with the key">
                  <TextInput list="api-models" aria-label="Model" className="w-72" value={model ?? s.api.model} onChange={(e) => setModelText(e.target.value)} />
                </Field>
                <datalist id="api-models">{MODELS.map((m) => <option key={m} value={m} />)}</datalist>
                <Button variant="ghost" disabled={model === null || model === s.api.model || setModel.isPending} onClick={() => setModel.mutate(model ?? '', { onSuccess: () => setModelText(null) })}>Save model</Button>
              </div>
            </>
          )}
        </Panel>
      )}

      {s?.selected === 'cli' && (
        <Panel title="Subscription usage"><LimitMeters /></Panel>
      )}
    </div>
  )
}
