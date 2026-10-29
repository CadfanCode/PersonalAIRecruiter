import { useState } from 'react'
import { type StageModel, type TestResult, CLI_MODELS, useConnection, useSetCliModel, useSetStageModel, useTestConnection } from '../../api/connection'
import { Button } from '../Form'
import { LimitMeters } from '../LimitMeters'
import { Panel } from '../Panel'
import { CheckIcon } from '../ui/Icons'

const STAGE_HINT: Record<StageModel['stage'], string> = {
  SEARCH: 'Search plans, role ideas and reading pasted ads.',
  RANKING: 'Scoring every ad. The most calls, so a lighter model saves the most.',
  CV_READING: 'Splitting an uploaded CV into entries and facts.',
  DOCUMENTS: 'Writing and revising the CV and cover letter.',
  AUTOFILL: 'Filling in application forms.',
}

const SELECT = 'rounded-xl border border-[var(--line)] bg-[var(--panel)] px-3 py-2 text-sm'

/** Claude Code on this computer: whether it is found, which model it uses, and a real test. */
export function ConnectionPanel() {
  const conn = useConnection()
  const setCliModel = useSetCliModel()
  const setStageModel = useSetStageModel()
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
          </>
        )}
      </Panel>

      {s && (
        <Panel title="Models">
          <p className="mb-4 text-sm text-[var(--muted)]">Pick a different model for each part. A stronger one writes better, a lighter one is faster and uses less of your plan. Changes apply from the next request.</p>
          <div className="grid gap-3">
            <ModelRow label="Default" hint="Used wherever a part below follows the default." value={s.cli.model} disabled={setCliModel.isPending}
              options={CLI_MODELS} onChange={(m) => setCliModel.mutate(m)} />
            {s.cli.stageModels.map((st) => (
              <ModelRow key={st.stage} label={st.label} hint={STAGE_HINT[st.stage]} value={st.model} disabled={setStageModel.isPending}
                options={[{ id: '', label: 'Same as default' }, ...CLI_MODELS.filter((m) => m.id !== '')]} onChange={(m) => setStageModel.mutate({ stage: st.stage, model: m })} />
            ))}
          </div>
          {(setCliModel.isError || setStageModel.isError) && <p role="alert" className="mt-3 text-sm text-[var(--danger)]">{(setCliModel.error ?? setStageModel.error)?.message}</p>}
        </Panel>
      )}

      <Panel title="Subscription usage"><LimitMeters /></Panel>
    </div>
  )
}

function ModelRow({ label, hint, value, options, disabled, onChange }: {
  label: string
  hint: string
  value: string
  options: { id: string; label: string }[]
  disabled: boolean
  onChange: (model: string) => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-1 border-b border-[var(--line)] pb-3 last:border-0 last:pb-0">
      <div className="min-w-0 flex-1 basis-56">
        <p className="text-sm font-medium">{label}</p>
        <p className="text-xs text-[var(--muted)]">{hint}</p>
      </div>
      <select aria-label={`${label} model`} className={SELECT} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        {options.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
        {!options.some((m) => m.id === value) && <option value={value}>{value}</option>}
      </select>
    </div>
  )
}
