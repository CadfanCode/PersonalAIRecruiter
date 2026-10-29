import { useSyncExternalStore } from 'react'

const SEEN = 'jobpilot.tourSeen'
let open = false
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())

export function tourSeen(): boolean {
  try {
    return localStorage.getItem(SEEN) === '1'
  } catch {
    return false
  }
}

export function openTour() {
  open = true
  emit()
}

/** Closing counts as having seen it, so it is offered automatically only once. */
export function closeTour() {
  open = false
  try {
    localStorage.setItem(SEEN, '1')
  } catch {
    // storage can be blocked; the tour is then offered again next time
  }
  emit()
}

export const useTourOpen = () =>
  useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l) }, () => open)
