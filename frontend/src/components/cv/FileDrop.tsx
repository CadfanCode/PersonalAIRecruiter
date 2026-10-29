import { useRef, useState } from 'react'
import { Button } from '../Form'
import { FileIcon, UploadIcon } from '../ui/Icons'

const ACCEPT = ['.pdf', '.docx', '.txt', '.md']

const isAccepted = (f: File) => ACCEPT.some((ext) => f.name.toLowerCase().endsWith(ext))

const size = (f: File) => (f.size > 1024 * 1024 ? `${(f.size / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(f.size / 1024))} KB`)

/** A drop zone with a real "Browse files" button, and the chosen files listed with a Remove button each. Shared by setup and the CV database. */
export function FileDrop({ files, onFiles, label = 'CV files', title = 'Drop your CV here' }: {
  files: File[]
  onFiles: (files: File[]) => void
  label?: string
  title?: string
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
      <div
        onDragOver={(e) => { e.preventDefault(); setOver(true) }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); add(e.dataTransfer.files) }}
        className={`flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed px-6 py-10 text-center transition-colors ${
          over ? 'border-[var(--accent)] bg-[var(--accent-soft)]' : 'border-[var(--line-strong)] bg-[var(--panel-2)]'}`}
      >
        <span className="flex h-11 w-11 items-center justify-center rounded-full bg-[var(--accent-soft)] text-[var(--accent)]">
          <UploadIcon width={20} height={20} />
        </span>
        <p className="text-[15px] font-medium">{title}</p>
        <p className="text-sm text-[var(--muted)]">PDF, Word, TXT or MD. Add several files if you like.</p>
        <Button onClick={() => input.current?.click()}>Browse files</Button>
        <input ref={input} type="file" multiple hidden aria-label={label} accept={ACCEPT.join(',')}
          onChange={(e) => { add(e.target.files ?? []); e.target.value = '' }} />
      </div>

      {rejected.length > 0 && (
        <p role="alert" className="mt-3 text-sm text-[var(--danger)]">
          {rejected.join(', ')} {rejected.length === 1 ? 'is' : 'are'} not supported. Use PDF, DOCX, TXT or MD.
        </p>
      )}

      {files.length > 0 && (
        <ul aria-label="Chosen files" className="mt-4 flex flex-col gap-2">
          {files.map((f) => (
            <li key={`${f.name}-${f.size}`} className="flex items-center gap-3 rounded-lg border border-[var(--line)] bg-[var(--panel)] px-4 py-2.5 text-sm">
              <FileIcon className="text-[var(--accent)]" />
              <span className="min-w-0 flex-1 truncate font-medium">{f.name}</span>
              <span className="text-[var(--muted)]">{size(f)}</span>
              <button type="button" aria-label={`Remove ${f.name}`} onClick={() => onFiles(files.filter((x) => x !== f))}
                className="rounded-md px-2 py-1 text-[var(--muted)] transition-colors hover:bg-[var(--danger-soft)] hover:text-[var(--danger)]">
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
