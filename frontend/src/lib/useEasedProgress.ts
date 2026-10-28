import { useEffect, useState } from 'react'

/** The reading takes a minute and reports no progress, so the ring eases towards 90% instead of pretending to know. */
export function useEasedProgress(running: boolean) {
  const [pct, setPct] = useState(4)
  useEffect(() => {
    if (!running) return
    const t = window.setInterval(() => setPct((p) => p + (90 - p) * 0.045), 800)
    return () => window.clearInterval(t)
  }, [running])
  return pct
}

