import { Button } from '../../components/Form'
import { FileDrop } from '../../components/cv/FileDrop'

/** Step 1: drag and drop or browse. The files are only held here; reading them waits until Claude is connected (step 2). */
export function UploadStep({ files, onFiles, existingEntries, onNext }: {
  files: File[]
  onFiles: (files: File[]) => void
  existingEntries: number
  onNext: () => void
}) {
  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Start with your CV</h1>
      <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-[var(--muted)]">
        We break your CV down into a personal database of your experience, education and skills. For every job you apply to,
        AI picks the most relevant parts to write a tailored CV and cover letter. Nothing is invented.
      </p>

      <div className="mt-7"><FileDrop files={files} onFiles={onFiles} /></div>

      {existingEntries > 0 && files.length === 0 && (
        <p className="mt-5 rounded-lg bg-[var(--success-soft)] px-4 py-3 text-sm text-[var(--success)]">
          Your CV database already has {existingEntries} {existingEntries === 1 ? 'entry' : 'entries'}. You can continue, or add more files.
        </p>
      )}

      <div className="mt-8 flex justify-end">
        <Button size="lg" onClick={onNext} disabled={files.length === 0 && existingEntries === 0}>Continue</Button>
      </div>
    </div>
  )
}
