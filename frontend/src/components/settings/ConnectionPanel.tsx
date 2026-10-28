import { useState } from 'react'
import { type TestResult, CLI_MODELS, useConnection, useSetCliModel, useTestConnection } from '../../api/connection'
import { Button, Field } from '../Form'
import { LimitMeters } from '../LimitMeters'
import { Panel } from '../Panel'
import { CheckIcon } from '../ui/Icons'

const SELECT = 'rounded-xl border border-[var(--line)] bg-[var(--panel)] px-3 py-2 text-sm'

/** Claude Code on this computer: whether it is found, which model it uses, and a real test. */
export function ConnectionPanel() {
  const conn = useConnection()
  const setCliModel = useSetCliModel()
  const test = useTestConnection()
  const [result, setResult] = useState<TestResult | null>(null)
  const s = conn.data

  return (
    <div className="grid gap-5">
      <Panel title="Claude connection">
        {!s ? <p className="text-sm text-[var(--muted)]">Loading…</p> : (
          <>
            <p className="text-sm text-[var(--muted)]">
              {s.cli.found ? <>Found {s.cli.version ?? 'Claude Code'} at <code>{s.cli.path}</code>.</> : <>Claude Code was not found. {s.cli.help}</>}
            </p>
            <div className="mt-5 flex flex-wrap items-center gap-3">
              <Button onClick={() => test.mutate(undefined, { onSuccess: setResult })} disabled={test.isPending}>{test.isPending ? 'Testing…' : 'Test the connection'}</Button>
              {result?.ok && <span role="status" className="flex items-center gap-1.5 text-sm font-medium text-[var(--success)]"><CheckIcon width={16} height={16} /> Connected</span>}
            </div>
            {result && !result.ok && (
              <div role="alert" className="mt-3 rounded-lg bg-[var(--danger-soft)] px-4 py-3 text-sm text-[var(--danger)]">
                <p className="font-medium">{result.message}</p>
                {result.help && <p className="mt-1 text-[var(--ink)]">{result.help}</p>}
              </div>
            )}
            <div className="mt-5">
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
      </Panel>

      <Panel title="Subscription usage"><LimitMeters /></Panel>
    </div>
  )
}
