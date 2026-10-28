import { useCallback, useState } from 'react'

const drafts = new Map<string, unknown>()

/**
 * Like useState, but the value outlives the screen: it is kept for as long as the app window is open, so text typed into a form is
 * still there after a trip to another screen. Call `clear` once the form has been submitted.
 */
export function useDraft<T>(key: string, initial: T) {
  const [value, setValue] = useState<T>(() => (drafts.has(key) ? (drafts.get(key) as T) : initial))
  const set = useCallback((v: T) => {
    drafts.set(key, v)
    setValue(v)
  }, [key])
  const clear = useCallback(() => {
    drafts.delete(key)
  }, [key])
  return [value, set, clear] as const
}
