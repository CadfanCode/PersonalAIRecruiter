import { describe, expect, it } from 'vitest'
import { stageOf } from './flow'

describe('which stage a page belongs to', () => {
  it('maps the pages of the pipeline to its five stages', () => {
    expect(stageOf('/')).toBe('mode')
    expect(stageOf('/runs')).toBe('mode')
    expect(stageOf('/runs/12')).toBe('run')
    expect(stageOf('/documents')).toBe('documents')
    expect(stageOf('/documents/job/4')).toBe('documents')
    expect(stageOf('/apply')).toBe('apply')
    expect(stageOf('/submit')).toBe('submit')
  })

  it('counts the "not started yet" page of a stage as that stage', () => {
    expect(stageOf('/not-started/documents')).toBe('documents')
    expect(stageOf('/not-started/submit')).toBe('submit')
    expect(stageOf('/not-started/nonsense')).toBeNull()
  })

  it('leaves the pages you maintain outside the flow, and does not mistake Submitted applications for the Submit stage', () => {
    expect(stageOf('/submitted')).toBeNull()
    expect(stageOf('/submitted/3')).toBeNull()
    expect(stageOf('/cv')).toBeNull()
    expect(stageOf('/settings')).toBeNull()
    expect(stageOf('/aktivitet')).toBeNull()
    expect(stageOf('/extras')).toBeNull()
  })
})
