import { useRef, useState } from 'react'
import { Button } from '../../components/Form'
import { FileIcon, UploadIcon } from '../../components/ui/Icons'

const ACCEPT = ['.pdf', '.docx', '.txt', '.md']

const isAccepted = (f: File) => ACCEPT.some((ext) => f.name.toLowerCase().endsWith(ext))

const size = (f: File) => (f.size > 1024 * 1024 ? `${(f.size / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(f.size / 1024))} KB`)

/** Step 1: drag and drop or browse. The files are only held here; reading them waits until Claude is connected (step 2). */
export function UploadStep({ files, onFiles, existingEntries, onNext }: {
  files: File[]
  onFiles: (files: File[]) => void
  existingEntries: number
  onNext: () => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)
  const [rejected, setRejected] = useState<string[]>([])

  const add = (list: FileList | File[]) => {
    const all = Array.from(list)
    const ok = all.filter(isAccepted)
    setRejected(all.filter((f) => !isAccepted(f)).map((f) => f.name))
    const merged = [...files]
    for (const f of ok) if (!merged.some((m) => m.name === f.name && m.size === f.size)) merged.push(f)
    onFiles(merged)
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Start with your CV</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">
        We'll break your CV down into a personal database of your experience, education and skills. For every job you apply to,
        AI picks the most relevant parts to create a tailored CV and cover letter. Nothing is invented: only what is in your CV is ever used.
      </p>

      <div
        onDragOver={(e) => { e.preventDefault(); setOver(true) }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); add(e.dataTransfer.files) }}
        className={`mt-7 flex flex-col items-center justify-center gap-3 rounded-3xl border border-dashed px-6 py-12 text-center transition ${
          over ? 'border-[var(--accent)] bg-[var(--accent-soft)]' : 'border-[var(--line)] bg-[var(--panel-2)] hover:border-[var(--muted)]/50'}`}
      >
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-[var(--accent-soft)] text-[var(--accent)]">
          <UploadIcon width={22} height={22} />
        </span>
        <p className="text-[15px] font-medium">Drop your CV here</p>
        <p className="text-sm text-[var(--muted)]">PDF or Word, and cover letters if you like. You can add several files.</p>
        <Button variant="ghost" onClick={() => input.current?.click()}>Browse files</Button>
        <input ref={input} type="file" multiple hidden aria-label="CV files" accept={ACCEPT.join(',')}
          onChange={(e) => { add(e.target.files ?? []); e.target.value = '' }} />
      </div>

      {rejected.length > 0 && (
        <p role="alert" className="mt-3 text-sm text-[var(--danger)]">
          {rejected.join(', ')} {rejected.length === 1 ? 'is' : 'are'} not supported. Use PDF, DOCX, TXT or MD.
        </p>
      )}

      {files.length > 0 && (
        <ul aria-label="Chosen files" className="mt-5 flex flex-col gap-2">
          {files.map((f) => (
            <li key={`${f.name}-${f.size}`} className="flex items-center gap-3 rounded-2xl border border-[var(--line)] bg-[var(--panel)] px-4 py-3 text-sm">
              <FileIcon className="text-[var(--accent)]" />
              <span className="min-w-0 flex-1 truncate font-medium">{f.name}</span>
              <span className="text-[var(--muted)]">{size(f)}</span>
              <button type="button" aria-label={`Remove ${f.name}`} onClick={() => onFiles(files.filter((x) => x !== f))}
                className="rounded-full px-2 py-1 text-[var(--muted)] transition hover:bg-[var(--danger-soft)] hover:text-[var(--danger)]">
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}

      {existingEntries > 0 && files.length === 0 && (
        <p className="mt-5 rounded-2xl bg-[var(--success-soft)] px-4 py-3 text-sm text-[var(--success)]">
          Your CV database already has {existingEntries} {existingEntries === 1 ? 'entry' : 'entries'}. You can continue, or add more files.
        </p>
      )}

      <div className="mt-8 flex justify-end">
        <Button size="lg" onClick={onNext} disabled={files.length === 0 && existingEntries === 0}>Continue</Button>
      </div>
    </div>
  )
}
