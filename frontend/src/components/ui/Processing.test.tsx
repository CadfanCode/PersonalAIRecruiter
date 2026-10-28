import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { etaText, Processing } from './Processing'

describe('time left', () => {
  it('speaks in rounded, human terms and admits when it does not know', () => {
    expect(etaText(null)).toBeNull()
    expect(etaText(undefined)).toBeNull()
    expect(etaText(4)).toBe('A few seconds left')
    expect(etaText(42)).toBe('About 40 seconds left')
    expect(etaText(95)).toBe('About 2 minutes left')
    expect(etaText(600)).toBe('About 10 minutes left')
  })
})

afterEach(() => vi.useRealTimers())

describe('processing view', () => {
  it('shows the percentage, what is happening and the time left, and can be cancelled', async () => {
    const cancel = vi.fn()
    render(<Processing title="Scoring the ads" percent={62.4} message="Scored 5 of 8" etaSeconds={130} onCancel={cancel}><span>3 found</span></Processing>)

    expect(screen.getByRole('progressbar', { name: 'Scoring the ads progress' })).toHaveAttribute('aria-valuenow', '62')
    expect(screen.getByText('62%')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Scored 5 of 8')
    expect(screen.getByText('About 2 minutes left')).toBeInTheDocument()
    expect(screen.getByText('3 found')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(cancel).toHaveBeenCalled()
  })

  it('keeps the percentage within 0 to 100 and says it is estimating without a time', () => {
    const { rerender } = render(<Processing title="Working" percent={140} />)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100')
    rerender(<Processing title="Working" percent={-5} />)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
    expect(screen.getByText('Estimating the time left…')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('estimates the time left itself from how long this view has taken for the share that is done, when nobody gave a figure', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-28T10:00:00Z'))
    const { rerender } = render(<Processing title="Writing" percent={5} />)
    expect(screen.getByText('Estimating the time left…')).toBeInTheDocument() // too early to say

    vi.setSystemTime(new Date('2026-10-28T10:01:00Z')) // 60 s in, half done: about 60 s left
    rerender(<Processing title="Writing" percent={50} />)
    expect(screen.getByText('About 60 seconds left')).toBeInTheDocument()
  })

  it('does not guess when the caller knows there is no figure, and shows its own words instead', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-28T10:00:00Z'))
    const { rerender } = render(<Processing title="Reading" percent={50} etaSeconds={null} fallback="This usually takes a minute or two." />)
    vi.setSystemTime(new Date('2026-10-28T10:05:00Z'))
    rerender(<Processing title="Reading" percent={50} etaSeconds={null} fallback="This usually takes a minute or two." />)

    expect(screen.getByText('This usually takes a minute or two.')).toBeInTheDocument()
  })
})
