import {
  type ColumnDef,
  type ColumnFiltersState,
  type FilterFn,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  type RowSelectionState,
  type SortingState,
  useReactTable,
} from '@tanstack/react-table'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useCreateDocuments } from '../../api/applications'
import { JOB_STATUSES, type JobRow, type JobStatus, usePatchJob } from '../../api/runs'
import { Button } from '../Form'
import { useToast } from '../Toasts'
import { JobDrawer } from './JobDrawer'
import { deadlineIsClose, shortDate } from './format'
import { ScoreBar } from './ScoreBar'
import { StatusChip } from './StatusChip'

interface SavedView {
  name: string
  sorting: SortingState
  columnFilters: ColumnFiltersState
  globalFilter: string
  /** How many of the best-ranked ads the view shows; null shows every ad. Older saved views have showAll instead. */
  limit?: number | null
  showAll?: boolean
}

const VIEWS_KEY = 'jobpilot.views'

function loadViews(): SavedView[] {
  try {
    return JSON.parse(localStorage.getItem(VIEWS_KEY) ?? '[]') as SavedView[]
  } catch {
    return []
  }
}

const selectionKey = (runId: number) => `jobpilot.selection.${runId}`

/** The ticked rows of a run are remembered in this browser, so leaving the page and coming back keeps them. */
function loadSelection(runId: number, jobs: JobRow[]): RowSelectionState {
  try {
    const ids = JSON.parse(localStorage.getItem(selectionKey(runId)) ?? '[]') as number[]
    const known = new Set(jobs.map((j) => j.id))
    return Object.fromEntries(ids.filter((id) => known.has(id)).map((id) => [String(id), true]))
  } catch {
    return {}
  }
}

function storeSelection(runId: number, selection: RowSelectionState) {
  try {
    const ids = Object.keys(selection).filter((k) => selection[k]).map(Number)
    if (ids.length === 0) localStorage.removeItem(selectionKey(runId))
    else localStorage.setItem(selectionKey(runId), JSON.stringify(ids))
  } catch {
    // private window or blocked storage: the selection simply is not remembered
  }
}

function storeViews(views: SavedView[]) {
  try {
    localStorage.setItem(VIEWS_KEY, JSON.stringify(views))
  } catch {
    // private window or blocked storage: views simply do not persist
  }
}

/** Relative column widths: the table is fixed-layout so every column fits at laptop widths, and long text wraps or is cut with a tooltip. */
const WEIGHT: Record<string, number> = {
  select: 2.5, rank: 4, score: 8, title: 13, employer: 9, role: 7, location: 7, published: 7.5, deadline: 7.5, language: 3.5,
  experience: 8.5, fit: 9, gaps: 9, source: 7, status: 8,
}

/** The best-ranked ads first; unranked ads last, and among equals the higher score. */
function byRank(a: JobRow, b: JobRow): number {
  return (a.rank ?? Infinity) - (b.rank ?? Infinity) || (b.score?.total ?? -1) - (a.score?.total ?? -1)
}

const minScore: FilterFn<JobRow> = (row, _id, value: number) => (row.original.score?.total ?? -1) >= value
const textMatch: FilterFn<JobRow> = (row, _id, value: string) => {
  const r = row.original
  return [r.title, r.employer, r.clientEmployer, r.municipality].filter(Boolean).join(' ').toLowerCase().includes(value.toLowerCase())
}

export function ResultsTable({
  runId,
  jobs,
  topN = 10,
  now = new Date(),
  defaultMinScore,
  preselectAll,
}: {
  runId: number
  jobs: JobRow[]
  topN?: number
  now?: Date
  /** Discover runs start with a minimum score filter, so the broad search does not bury the good ads. */
  defaultMinScore?: number
  /** A run with a single pasted job starts with that job ticked, so the next step is one click away. */
  preselectAll?: boolean
}) {
  const [sorting, setSorting] = useState<SortingState>([{ id: 'rank', desc: false }])
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>(() =>
    defaultMinScore ? [{ id: 'score', value: defaultMinScore }] : [])
  const [globalFilter, setGlobalFilter] = useState('')
  const [rowSelection, setRowSelection] = useState<RowSelectionState>(() => {
    const stored = loadSelection(runId, jobs)
    return preselectAll && Object.keys(stored).length === 0 && localStorage.getItem(selectionKey(runId)) === null
      ? Object.fromEntries(jobs.map((j) => [String(j.id), true]))
      : stored
  })
  // null shows every ad. Discover runs start on their minimum-score filter, everything else on the top N.
  const [limit, setLimit] = useState<number | null>(defaultMinScore ? null : topN)
  const [openId, setOpenId] = useState<number | null>(null)
  const [activeIndex, setActiveIndex] = useState(0)
  const [views, setViews] = useState<SavedView[]>(loadViews)
  const patch = usePatchJob(runId)
  const toast = useToast()
  const navigate = useNavigate()
  const createDocuments = useCreateDocuments()

  const hasRoles = jobs.some((j) => j.roleType)
  const roleTypes = useMemo(() => [...new Set(jobs.flatMap((j) => (j.roleType ? [j.roleType] : [])))].sort(), [jobs])
  const data = jobs

  const columns = useMemo<ColumnDef<JobRow>[]>(
    () => [
      {
        id: 'select',
        enableSorting: false,
        header: ({ table }) => (
          <input type="checkbox" aria-label="Select all rows" checked={table.getIsAllRowsSelected()}
            onChange={table.getToggleAllRowsSelectedHandler()} />
        ),
        cell: ({ row }) => (
          <input type="checkbox" aria-label={`Select ${row.original.title}`} checked={row.getIsSelected()}
            onClick={(e) => e.stopPropagation()} onChange={row.getToggleSelectedHandler()} />
        ),
      },
      { id: 'rank', header: 'Rank', accessorFn: (r) => r.rank ?? undefined, sortUndefined: 'last',
        cell: ({ row }) => row.original.rank ?? '' },
      {
        id: 'score', header: 'Score', accessorFn: (r) => r.score?.total ?? undefined, sortUndefined: 'last',
        filterFn: minScore, cell: ({ row }) => <ScoreBar score={row.original.score} />,
      },
      { id: 'title', header: 'Title', accessorKey: 'title',
        cell: ({ row }) => <Wrap text={row.original.title} className="font-medium" /> },
      {
        id: 'employer', header: 'Employer', accessorFn: (r) => r.employer ?? '',
        cell: ({ row }) => (
          <span title={[row.original.employer, row.original.clientEmployer].filter(Boolean).join(' ')}>
            <Wrap text={row.original.employer} />
            {row.original.clientEmployer && <span className="block truncate text-xs text-[var(--muted)]">({row.original.clientEmployer})</span>}
          </span>
        ),
      },
      ...(hasRoles
        ? [{
            id: 'role', header: 'Role type', accessorFn: (r: JobRow) => r.roleType ?? '', filterFn: 'equalsString' as const,
            cell: ({ row }: { row: { original: JobRow } }) => {
              const all = row.original.roleTypes ?? []
              const others = all.filter((t) => t !== row.original.roleType)
              return (
                <span>
                  {row.original.roleType}
                  {others.length > 0 && <span className="block text-xs text-[var(--muted)]" title={all.join(', ')}>also found for {others.join(', ')}</span>}
                </span>
              )
            },
          } satisfies ColumnDef<JobRow>]
        : []),
      { id: 'location', header: 'Location', accessorFn: (r) => r.municipality ?? r.location ?? '',
        cell: ({ getValue }) => <Wrap text={getValue<string>()} /> },
      { id: 'published', header: 'Published', accessorFn: (r) => r.publishedAt ?? '', cell: ({ row }) => shortDate(row.original.publishedAt) },
      {
        id: 'deadline', header: 'Deadline', accessorFn: (r) => r.deadline ?? '9999', sortUndefined: 'last',
        cell: ({ row }) => {
          const close = deadlineIsClose(row.original.deadline, now)
          return <span className={close ? 'font-semibold text-[var(--danger)]' : ''}>{shortDate(row.original.deadline)}</span>
        },
      },
      {
        id: 'language', header: 'Lang', accessorFn: (r) => r.language ?? '', filterFn: 'equalsString',
        cell: ({ row }) => (
          <span title={row.original.language ?? ''} aria-label={row.original.language ?? 'unknown'}>
            {row.original.language ? <span className="rounded bg-[var(--hover)] px-1.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-[var(--muted)]">{row.original.language}</span> : ''}
          </span>
        ),
      },
      {
        id: 'experience', header: 'Experience asked', accessorFn: (r) => r.experienceRequiredText ?? '',
        cell: ({ row }) => <Clamp text={row.original.experienceRequiredText} />,
      },
      { id: 'fit', header: 'Fit', accessorFn: (r) => r.score?.fit ?? '', enableSorting: false, cell: ({ row }) => <Clamp text={row.original.score?.fit} /> },
      { id: 'gaps', header: 'Gaps', accessorFn: (r) => r.score?.gaps ?? '', enableSorting: false, cell: ({ row }) => <Clamp text={row.original.score?.gaps} /> },
      {
        id: 'source', header: 'Source', accessorKey: 'source',
        cell: ({ row }) => (
          <span title={row.original.source ?? ''}>
            <span className="block truncate">{row.original.source}</span>
            {row.original.dateUnverified && <span className="block text-xs text-[var(--warn)]">date unverified</span>}
          </span>
        ),
      },
      { id: 'status', header: 'Status', accessorKey: 'status', filterFn: 'equalsString',
        cell: ({ row }) => <StatusChip status={row.original.status} /> },
    ],
    [now, hasRoles],
  )

  const table = useReactTable({
    data,
    columns,
    state: { sorting, columnFilters, globalFilter, rowSelection },
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    onGlobalFilterChange: setGlobalFilter,
    onRowSelectionChange: (updater) =>
      setRowSelection((old) => {
        const next = typeof updater === 'function' ? updater(old) : updater
        storeSelection(runId, next)
        return next
      }),
    globalFilterFn: textMatch,
    getRowId: (r) => String(r.id),
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
  })

  const filterValue = (id: string) => columnFilters.find((f) => f.id === id)?.value as string | number | undefined
  // "Top N" is decided by rank among the ads that pass the filters, whatever column the table is sorted by.
  const filteredRows = table.getFilteredRowModel().rows
  const topIds = useMemo(
    () => (limit === null ? null : new Set([...filteredRows].sort((a, b) => byRank(a.original, b.original)).slice(0, limit).map((r) => r.id))),
    [filteredRows, limit],
  )
  const sortedRows = table.getRowModel().rows
  const rows = topIds ? sortedRows.filter((r) => topIds.has(r.id)) : sortedRows
  const minScoreFilter = filterValue('score') as number | undefined
  const hiddenByScore = minScoreFilter === undefined ? 0 : jobs.filter((j) => !minScore(
    { original: j } as never, 'score', minScoreFilter, () => undefined)).length
  const setFilter = (id: string, value: string | number | undefined) =>
    setColumnFilters((f) => [...f.filter((x) => x.id !== id), ...(value === undefined || value === '' ? [] : [{ id, value }])])

  const setStatus = (id: number, status: JobStatus) =>
    patch.mutate({ id, status }, { onError: (e) => toast(e.message, 'error') })

  // j/k move, s shortlists, Enter opens, g is reserved for "generate documents" (milestone 4).
  const stateRef = useRef({ rows, activeIndex })
  stateRef.current = { rows, activeIndex }
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.closest('input, textarea, select') || e.metaKey || e.ctrlKey || e.altKey) return
      const { rows: r, activeIndex: i } = stateRef.current
      if (e.key === 'j') setActiveIndex(Math.min(i + 1, r.length - 1))
      else if (e.key === 'k') setActiveIndex(Math.max(i - 1, 0))
      else if (e.key === 's' && r[i]) setStatus(r[i].original.id, 'SHORTLISTED')
      else if (e.key === 'Enter' && r[i]) setOpenId(r[i].original.id)
      else if (e.key === 'g' && r[i]) navigate(`/documents/job/${r[i].original.id}?from=${runId}`)
      else if (e.key === 'Escape') setOpenId(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const saveView = () => {
    const name = window.prompt('Name this view')?.trim()
    if (!name) return
    const next = [...views.filter((v) => v.name !== name), { name, sorting, columnFilters, globalFilter, limit }]
    setViews(next)
    storeViews(next)
  }
  const applyView = (name: string) => {
    const v = views.find((x) => x.name === name)
    if (!v) return
    setSorting(v.sorting)
    setColumnFilters(v.columnFilters)
    setGlobalFilter(v.globalFilter)
    setLimit(v.limit !== undefined ? v.limit : v.showAll ? null : topN)
  }
  const deleteView = (name: string) => {
    const next = views.filter((v) => v.name !== name)
    setViews(next)
    storeViews(next)
  }

  const selectedCount = Object.keys(rowSelection).length
  const appliedSelected = table.getSelectedRowModel().rows.filter((r) => r.original.status === 'APPLIED')

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <input
          aria-label="Search ads"
          placeholder="Search title, employer, place"
          value={globalFilter}
          onChange={(e) => setGlobalFilter(e.target.value)}
          className="w-64 rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2.5 py-1.5 text-sm"
        />
        <select aria-label="Filter by status" value={(filterValue('status') as string) ?? ''}
          onChange={(e) => setFilter('status', e.target.value)}
          className="rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2 py-1.5 text-sm">
          <option value="">All statuses</option>
          {JOB_STATUSES.map((s) => (
            <option key={s} value={s}>{s.replace('_', ' ').toLowerCase()}</option>
          ))}
        </select>
        <select aria-label="Filter by language" value={(filterValue('language') as string) ?? ''}
          onChange={(e) => setFilter('language', e.target.value)}
          className="rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2 py-1.5 text-sm">
          <option value="">Any language</option>
          <option value="sv">Swedish</option>
          <option value="en">English</option>
        </select>
        {hasRoles && (
          <select aria-label="Filter by role type" value={(filterValue('role') as string) ?? ''}
            onChange={(e) => setFilter('role', e.target.value)}
            className="rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2 py-1.5 text-sm">
            <option value="">All role types</option>
            {roleTypes.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        )}
        <label className="flex items-center gap-1.5 text-sm text-[var(--muted)]">
          Min score
          <input aria-label="Minimum score" type="number" min={0} max={100} value={(filterValue('score') as number) ?? ''}
            onChange={(e) => setFilter('score', e.target.value === '' ? undefined : Number(e.target.value))}
            className="w-16 rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2 py-1 text-sm" />
        </label>
        <span role="group" aria-label="How many ads to show" className="flex items-center gap-1">
          <Button variant="ghost" aria-pressed={limit === topN && minScoreFilter === undefined}
            onClick={() => { setFilter('score', undefined); setLimit(topN) }}>
            Show top {topN}
          </Button>
          <Button variant="ghost" aria-pressed={limit === null && minScoreFilter === undefined}
            onClick={() => { setFilter('score', undefined); setLimit(null) }}>
            Show all ({jobs.length})
          </Button>
        </span>
        {minScoreFilter !== undefined && (
          <button type="button" onClick={() => setFilter('score', undefined)}
            className="flex items-center gap-1.5 rounded-full bg-[var(--accent-soft)] px-3 py-1 text-xs font-medium text-[var(--accent)]"
            aria-label={`Remove the minimum score ${minScoreFilter}`}>
            Hiding {hiddenByScore} {hiddenByScore === 1 ? 'ad' : 'ads'} below score {minScoreFilter} <span aria-hidden>×</span>
          </button>
        )}
        <span className="ml-auto flex items-center gap-2">
          <select aria-label="Saved views" value="" onChange={(e) => applyView(e.target.value)}
            className="rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2 py-1.5 text-sm">
            <option value="">Saved views</option>
            {views.map((v) => <option key={v.name}>{v.name}</option>)}
          </select>
          <Button variant="ghost" onClick={saveView}>Save view</Button>
          {views.length > 0 && (
            <Button variant="ghost" aria-label="Delete the last saved view" onClick={() => deleteView(views[views.length - 1].name)}>
              Delete view
            </Button>
          )}
          <a className="text-sm text-[var(--accent)] underline" href={`/api/runs/${runId}/jobs.csv`}>Export CSV</a>
          <a className="text-sm text-[var(--accent)] underline" href={`/api/runs/${runId}/jobs.xlsx`}>Export XLSX</a>
        </span>
      </div>

      {selectedCount > 0 && (
        <div role="region" aria-label="Selected jobs"
          className="sticky top-16 z-20 mb-4 rounded-2xl border border-[var(--accent)]/50 bg-[var(--glass)] px-4 py-3 text-sm shadow-[var(--shadow-lift)] backdrop-blur-xl">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              disabled={createDocuments.isPending}
              title="Writes a tailored CV and cover letter for each selected job, then takes you to them"
              onClick={() => createDocuments.mutate(
                { jobIds: table.getSelectedRowModel().rows.map((r) => r.original.id), runId },
                { onSuccess: () => { table.resetRowSelection(true); navigate('/documents?new=1') }, onError: (e) => toast(e.message, 'error') },
              )}>
              {createDocuments.isPending
                ? 'Starting...'
                : `Create tailored documents for ${selectedCount} selected ${selectedCount === 1 ? 'job' : 'jobs'}`}
            </Button>
            <span className="text-[var(--muted)]">{selectedCount} selected</span>
            <span className="ml-auto flex items-center gap-2">
              <Button variant="ghost" onClick={() => {
                table.getSelectedRowModel().rows.forEach((r) => setStatus(r.original.id, 'SHORTLISTED'))
                table.resetRowSelection(true)
              }}>Shortlist selected</Button>
              <Button variant="ghost" disabled={appliedSelected.length === 0}
                title={appliedSelected.length === 0 ? 'Only jobs marked as applied can be logged' : 'Opens the confirmation step; nothing is saved yet'}
                onClick={() => navigate(`/aktivitet?jobs=${appliedSelected.map((r) => r.original.id).join(',')}`)}>
                Log in aktivitetsrapport ({appliedSelected.length})
              </Button>
              <Button variant="ghost" onClick={() => table.resetRowSelection(true)}>Clear</Button>
            </span>
          </div>
          <ul className="mt-2 flex flex-wrap gap-1.5" aria-label="Jobs to continue with">
            {table.getSelectedRowModel().rows.map((r) => (
              <li key={r.id} className="flex items-center gap-1 rounded-full bg-navy/10 py-0.5 pl-2.5 pr-1 text-xs">
                <span className="max-w-[16rem] truncate">{r.original.title}{r.original.employer ? `, ${r.original.employer}` : ''}</span>
                <button type="button" aria-label={`Deselect ${r.original.title}`} onClick={() => r.toggleSelected(false)}
                  className="rounded-full px-1.5 text-[var(--muted)] hover:bg-navy/20 hover:text-[var(--ink)]">×</button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="overflow-x-auto rounded-2xl border border-[var(--line)] bg-[var(--panel)]">
        <table className="w-full min-w-[1100px] table-fixed border-collapse text-left text-sm">
          <colgroup>
            {table.getVisibleLeafColumns().map((c) => (
              <col key={c.id} style={{ width: `${(WEIGHT[c.id] ?? 8) / table.getVisibleLeafColumns().reduce((n, x) => n + (WEIGHT[x.id] ?? 8), 0) * 100}%` }} />
            ))}
          </colgroup>
          <thead>
            {table.getHeaderGroups().map((hg) => (
              <tr key={hg.id} className="border-b border-[var(--line)] text-xs tracking-wide text-[var(--muted)]">
                {hg.headers.map((h) => (
                  <th key={h.id} className="px-2 py-2 align-bottom font-semibold leading-tight" aria-sort={
                    h.column.getIsSorted() === 'asc' ? 'ascending' : h.column.getIsSorted() === 'desc' ? 'descending' : undefined}>
                    {h.column.getCanSort() ? (
                      <button type="button" onClick={h.column.getToggleSortingHandler()} className="flex items-center gap-1">
                        {flexRender(h.column.columnDef.header, h.getContext())}
                        <span aria-hidden>{{ asc: '▲', desc: '▼' }[h.column.getIsSorted() as string] ?? ''}</span>
                      </button>
                    ) : (
                      flexRender(h.column.columnDef.header, h.getContext())
                    )}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr
                key={row.id}
                data-active={i === activeIndex}
                onClick={() => { setActiveIndex(i); setOpenId(row.original.id) }}
                className={`cursor-pointer border-b border-[var(--line)] align-top hover:bg-[var(--surface)] ${
                  i === activeIndex ? 'bg-navy/5 outline outline-1 -outline-offset-1 outline-[var(--accent)]' : ''}`}
              >
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className="overflow-hidden px-2 py-2">{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
                ))}
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={columns.length} className="px-3 py-8 text-center text-[var(--muted)]">No ads match.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-xs text-[var(--muted)]">
        Keys: j/k move, Enter opens, s shortlists, g opens the documents (generating them on first use). Showing {rows.length} of {jobs.length}.
      </p>

      <JobDrawer runId={runId} jobId={openId} onClose={() => setOpenId(null)} />
    </div>
  )
}

/** Long text that wraps within its column and is cut after three lines; the full text is the tooltip. */
function Clamp({ text }: { text: string | null | undefined }) {
  if (!text) return null
  return <span className="line-clamp-3 break-words text-xs text-[var(--muted)]" title={text}>{text}</span>
}

/** Names and places: wrap over two lines, then cut with the full text as the tooltip. */
function Wrap({ text, className = '' }: { text: string | null | undefined; className?: string }) {
  if (!text) return null
  return <span className={`line-clamp-2 break-words ${className}`} title={text}>{text}</span>
}
