import { useCallback, useState } from 'react'

const drafts = new Map<string, unknown>()

/** For tests: every test starts without what the previous one typed. */
export const clearAllDrafts = () => drafts.clear()

/** Forgets every draft whose key starts with the prefix (for example everything typed about a job that was deleted). */
export const clearDraftsWithPrefix = (prefix: string) => {
  for (const key of [...drafts.keys()]) if (key.startsWith(prefix)) drafts.delete(key)
}

/**
 * Like useState, but the value outlives the screen: it is kept for as long as the app window is open, so text typed into a form is
 * still there after a trip to another screen. Call `clear` once the form has been submitted.
 */
export function useDraft<T>(key: string, initial: T) {
  const [value, setValue] = useState<T>(() => (drafts.has(key) ? (drafts.get(key) as T) : initial))
  const set = useCallback((v: T | ((prev: T) => T)) => {
    const next = typeof v === 'function' ? (v as (prev: T) => T)(drafts.has(key) ? (drafts.get(key) as T) : initial) : v
    drafts.set(key, next)
    setValue(next)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  const clear = useCallback(() => {
    drafts.delete(key)
  }, [key])
  return [value, set, clear] as const
}
