import type { SVGProps } from 'react'

const base = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const

type P = SVGProps<SVGSVGElement>

export const CheckIcon = (p: P) => (
  <svg {...base} {...p}><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
)
export const ChevronRightIcon = (p: P) => (
  <svg {...base} {...p}><path d="M9 6l6 6-6 6" /></svg>
)
export const ArrowRightIcon = (p: P) => (
  <svg {...base} {...p}><path d="M5 12h14M13 6l6 6-6 6" /></svg>
)
export const UploadIcon = (p: P) => (
  <svg {...base} {...p}><path d="M12 16V4M7 9l5-5 5 5M5 20h14" /></svg>
)
export const FileIcon = (p: P) => (
  <svg {...base} {...p}><path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8l-5-5zM14 3v5h5" /></svg>
)
export const SparkIcon = (p: P) => (
  <svg {...base} {...p}><path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3zM18 16l.8 2.2L21 19l-2.2.8L18 22l-.8-2.2L15 19l2.2-.8L18 16z" /></svg>
)
export const SearchIcon = (p: P) => (
  <svg {...base} {...p}><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /></svg>
)
export const KeyIcon = (p: P) => (
  <svg {...base} {...p}><circle cx="8" cy="15" r="4" /><path d="M11 12l9-9M16 7l3 3M14 9l2 2" /></svg>
)
export const TerminalIcon = (p: P) => (
  <svg {...base} {...p}><rect x="3" y="4" width="18" height="16" rx="3" /><path d="M7 10l3 2-3 2M13 15h4" /></svg>
)
export const GlobeIcon = (p: P) => (
  <svg {...base} {...p}><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z" /></svg>
)
export const UserIcon = (p: P) => (
  <svg {...base} {...p}><circle cx="12" cy="8" r="4" /><path d="M4 20c1.4-3.4 4.2-5 8-5s6.6 1.6 8 5" /></svg>
)
export const ArchiveIcon = (p: P) => (
  <svg {...base} {...p}><rect x="3" y="4" width="18" height="5" rx="1.5" /><path d="M5 9v9a2 2 0 002 2h10a2 2 0 002-2V9M10 13h4" /></svg>
)
export const GridIcon = (p: P) => (
  <svg {...base} {...p}><rect x="4" y="4" width="7" height="7" rx="2" /><rect x="13" y="4" width="7" height="7" rx="2" /><rect x="4" y="13" width="7" height="7" rx="2" /><rect x="13" y="13" width="7" height="7" rx="2" /></svg>
)
export const SlidersIcon = (p: P) => (
  <svg {...base} {...p}><path d="M5 7h9M18 7h1M5 17h1M10 17h9" /><circle cx="16" cy="7" r="2" /><circle cx="8" cy="17" r="2" /></svg>
)
export const LockIcon = (p: P) => (
  <svg {...base} {...p}><rect x="5" y="11" width="14" height="9" rx="2.5" /><path d="M8 11V8a4 4 0 018 0v3" /></svg>
)
export const BellIcon = (p: P) => (
  <svg {...base} {...p}><path d="M6 17V11a6 6 0 1112 0v6l1.5 2h-15L6 17zM10 21h4" /></svg>
)
export const WarnIcon = (p: P) => (
  <svg {...base} {...p}><path d="M12 4l9 16H3L12 4zM12 10v4M12 17.2v.1" /></svg>
)
