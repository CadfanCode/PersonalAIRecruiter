import { useState } from 'react'
import { type TestResult, useConnection, useSelectProvider, useTestConnection } from '../../api/connection'
import { Button } from '../../components/Form'
import { CheckIcon, WarnIcon } from '../../components/ui/Icons'
import { Pill } from '../../components/ui/Pill'

/** Step 2: connect the Claude Code subscription on this computer, test it for real, and only then let them go on. */
export function ConnectStep({ alreadyWorks, onBack, onNext }: { alreadyWorks: boolean; onBack?: () => void; onNext: () => void }) {
  const conn = useConnection()
  const select = useSelectProvider()
  const test = useTestConnection()
  const [result, setResult] = useState<TestResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const status = conn.data
  const busy = select.isPending || test.isPending
  const connected = result?.ok === true || (alreadyWorks && !result)

  const runTest = async () => {
    setError(null)
    try {
      await select.mutateAsync('cli')
      setResult(await test.mutateAsync(undefined))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Connect Claude</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">
        Claude does the reading, scoring and writing. JobPilot uses your own Claude Code subscription on this computer, so nothing goes through anyone else.
      </p>

      <div className="mt-6 rounded-xl border border-[var(--line)] bg-[var(--panel)] p-5">
        {!status ? (
          <p className="text-sm text-[var(--muted)]">Looking for Claude Code…</p>
        ) : status.cli.found ? (
          <p className="flex items-center gap-2 text-sm"><Pill tone="success"><CheckIcon width={12} height={12} /> Found</Pill> {status.cli.version}</p>
        ) : (
          <div>
            <p className="flex items-center gap-2 text-sm"><Pill tone="warn"><WarnIcon width={12} height={12} /> Not found</Pill> Claude Code is not installed on this computer yet.</p>
            {status.cli.help && <code className="mt-3 block overflow-x-auto rounded-lg bg-[var(--surface)] px-3 py-2 text-xs">{status.cli.help}</code>}
            <div className="mt-3"><Button variant="ghost" onClick={() => void conn.refetch()}>Check again</Button></div>
          </div>
        )}
        <div className="mt-4">
          <Button onClick={() => void runTest()} disabled={busy || (status ? !status.cli.found : true)}>
            {test.isPending ? 'Testing…' : 'Test the connection'}
          </Button>
        </div>
      </div>

      {connected && (
        <p role="status" className="mt-5 flex items-center gap-2 rounded-lg bg-[var(--success-soft)] px-4 py-3 text-sm font-medium text-[var(--success)]">
          <CheckIcon /> Connected. Claude answered.
        </p>
      )}
      {result && !result.ok && (
        <div role="alert" className="mt-5 rounded-lg bg-[var(--danger-soft)] px-4 py-3 text-sm text-[var(--danger)]">
          <p className="font-medium">{result.message}</p>
          {result.help && <p className="mt-1 text-[var(--ink)]">{result.help}</p>}
        </div>
      )}
      {error && <p role="alert" className="mt-5 rounded-lg bg-[var(--danger-soft)] px-4 py-3 text-sm text-[var(--danger)]">{error}</p>}

      <div className="mt-8 flex justify-between">
        {onBack ? <Button variant="ghost" onClick={onBack}>Back</Button> : <span />}
        <Button size="lg" onClick={onNext} disabled={!connected}>Continue</Button>
      </div>
    </div>
  )
}
