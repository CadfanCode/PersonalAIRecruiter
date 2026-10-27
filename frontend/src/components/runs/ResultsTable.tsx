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
import { deadlineIsClose, FLAG, shortDate } from './format'
import { ScoreBar } from './ScoreBar'
import { StatusChip } from './StatusChip'

interface SavedView {
  name: string
  sorting: SortingState
  columnFilters: ColumnFiltersState
  globalFilter: string
  showAll: boolean
}

const VIEWS_KEY = 'jobpilot.views'

function loadViews(): SavedView[] {
  try {
    return JSON.parse(localStorage.getItem(VIEWS_KEY) ?? '[]') as SavedView[]
  } catch {
    return []
  }
}

function storeViews(views: SavedView[]) {
  try {
    localStorage.setItem(VIEWS_KEY, JSON.stringify(views))
  } catch {
    // private window or blocked storage: views simply do not persist
  }
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
}: {
  runId: number
  jobs: JobRow[]
  topN?: number
  now?: Date
}) {
  const [sorting, setSorting] = useState<SortingState>([{ id: 'rank', desc: false }])
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([])
  const [globalFilter, setGlobalFilter] = useState('')
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({})
  const [showAll, setShowAll] = useState(false)
  const [openId, setOpenId] = useState<number | null>(null)
  const [activeIndex, setActiveIndex] = useState(0)
  const [views, setViews] = useState<SavedView[]>(loadViews)
  const patch = usePatchJob(runId)
  const toast = useToast()
  const navigate = useNavigate()
  const createDocuments = useCreateDocuments()

  const filtering = globalFilter !== '' || columnFilters.length > 0
  // The default view is the top N by score; any filter or "show all" widens it (SPEC 7.4).
  const data = useMemo(() => (showAll || filtering ? jobs : jobs.slice(0, topN)), [jobs, showAll, filtering, topN])

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
        cell: ({ row }) => <span className="font-medium">{row.original.title}</span> },
      {
        id: 'employer', header: 'Employer', accessorFn: (r) => r.employer ?? '',
        cell: ({ row }) => (
          <span>
            {row.original.employer}
            {row.original.clientEmployer && <span className="block text-xs text-[var(--muted)]">({row.original.clientEmployer})</span>}
          </span>
        ),
      },
      { id: 'location', header: 'Location', accessorFn: (r) => r.municipality ?? r.location ?? '' },
      { id: 'published', header: 'Published', accessorFn: (r) => r.publishedAt ?? '', cell: ({ row }) => shortDate(row.original.publishedAt) },
      {
        id: 'deadline', header: 'Deadline', accessorFn: (r) => r.deadline ?? '9999', sortUndefined: 'last',
        cell: ({ row }) => {
          const close = deadlineIsClose(row.original.deadline, now)
          return <span className={close ? 'font-semibold text-red-600' : ''}>{shortDate(row.original.deadline)}</span>
        },
      },
      {
        id: 'language', header: 'Lang', accessorFn: (r) => r.language ?? '', filterFn: 'equalsString',
        cell: ({ row }) => (
          <span title={row.original.language ?? ''} aria-label={row.original.language ?? 'unknown'}>
            {row.original.language ? FLAG[row.original.language] : ''}
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
          <span>
            {row.original.source}
            {row.original.dateUnverified && <span className="block text-xs text-amber-600">date unverified</span>}
          </span>
        ),
      },
      { id: 'status', header: 'Status', accessorKey: 'status', filterFn: 'equalsString',
        cell: ({ row }) => <StatusChip status={row.original.status} /> },
    ],
    [now],
  )

  const table = useReactTable({
    data,
    columns,
    state: { sorting, columnFilters, globalFilter, rowSelection },
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    onGlobalFilterChange: setGlobalFilter,
    onRowSelectionChange: setRowSelection,
    globalFilterFn: textMatch,
    getRowId: (r) => String(r.id),
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
  })

  const rows = table.getRowModel().rows
  const setFilter = (id: string, value: string | number | undefined) =>
    setColumnFilters((f) => [...f.filter((x) => x.id !== id), ...(value === undefined || value === '' ? [] : [{ id, value }])])
  const filterValue = (id: string) => columnFilters.find((f) => f.id === id)?.value as string | number | undefined

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
    const next = [...views.filter((v) => v.name !== name), { name, sorting, columnFilters, globalFilter, showAll }]
    setViews(next)
    storeViews(next)
  }
  const applyView = (name: string) => {
    const v = views.find((x) => x.name === name)
    if (!v) return
    setSorting(v.sorting)
    setColumnFilters(v.columnFilters)
    setGlobalFilter(v.globalFilter)
    setShowAll(v.showAll)
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
          className="w-64 rounded-md border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1.5 text-sm"
        />
        <select aria-label="Filter by status" value={(filterValue('status') as string) ?? ''}
          onChange={(e) => setFilter('status', e.target.value)}
          className="rounded-md border border-[var(--line)] bg-[var(--surface)] px-2 py-1.5 text-sm">
          <option value="">All statuses</option>
          {JOB_STATUSES.map((s) => (
            <option key={s} value={s}>{s.replace('_', ' ').toLowerCase()}</option>
          ))}
        </select>
        <select aria-label="Filter by language" value={(filterValue('language') as string) ?? ''}
          onChange={(e) => setFilter('language', e.target.value)}
          className="rounded-md border border-[var(--line)] bg-[var(--surface)] px-2 py-1.5 text-sm">
          <option value="">Any language</option>
          <option value="sv">Swedish</option>
          <option value="en">English</option>
        </select>
        <label className="flex items-center gap-1.5 text-sm text-[var(--muted)]">
          Min score
          <input aria-label="Minimum score" type="number" min={0} max={100} value={(filterValue('score') as number) ?? ''}
            onChange={(e) => setFilter('score', e.target.value === '' ? undefined : Number(e.target.value))}
            className="w-16 rounded-md border border-[var(--line)] bg-[var(--surface)] px-2 py-1 text-sm" />
        </label>
        <Button variant="ghost" onClick={() => setShowAll(!showAll)} disabled={jobs.length <= topN && !showAll}>
          {showAll ? `Show top ${topN}` : `Show all (${jobs.length})`}
        </Button>
        <span className="ml-auto flex items-center gap-2">
          <select aria-label="Saved views" value="" onChange={(e) => applyView(e.target.value)}
            className="rounded-md border border-[var(--line)] bg-[var(--surface)] px-2 py-1.5 text-sm">
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
          className="sticky top-0 z-10 mb-3 rounded-md border border-[var(--accent)] bg-[var(--panel)] px-3 py-3 text-sm shadow-sm">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              disabled={createDocuments.isPending}
              title="Writes a tailored CV and cover letter for each selected job, then takes you to them"
              onClick={() => createDocuments.mutate(
                { jobIds: table.getSelectedRowModel().rows.map((r) => r.original.id), runId },
                { onSuccess: () => { setRowSelection({}); navigate('/documents?new=1') }, onError: (e) => toast(e.message, 'error') },
              )}>
              {createDocuments.isPending
                ? 'Starting...'
                : `Create tailored documents for ${selectedCount} selected ${selectedCount === 1 ? 'job' : 'jobs'}`}
            </Button>
            <span className="text-[var(--muted)]">{selectedCount} selected</span>
            <span className="ml-auto flex items-center gap-2">
              <Button variant="ghost" onClick={() => {
                table.getSelectedRowModel().rows.forEach((r) => setStatus(r.original.id, 'SHORTLISTED'))
                setRowSelection({})
              }}>Shortlist selected</Button>
              <Button variant="ghost" disabled={appliedSelected.length === 0}
                title={appliedSelected.length === 0 ? 'Only jobs marked as applied can be logged' : 'Opens the confirmation step; nothing is saved yet'}
                onClick={() => navigate(`/aktivitet?jobs=${appliedSelected.map((r) => r.original.id).join(',')}`)}>
                Log in aktivitetsrapport ({appliedSelected.length})
              </Button>
              <Button variant="ghost" onClick={() => setRowSelection({})}>Clear</Button>
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

      <div className="overflow-x-auto rounded-lg border border-[var(--line)] bg-[var(--panel)]">
        <table className="w-full min-w-[1200px] border-collapse text-left text-sm">
          <thead>
            {table.getHeaderGroups().map((hg) => (
              <tr key={hg.id} className="border-b border-[var(--line)] text-xs uppercase tracking-wide text-[var(--muted)]">
                {hg.headers.map((h) => (
                  <th key={h.id} className="px-3 py-2 font-semibold" aria-sort={
                    h.column.getIsSorted() === 'asc' ? 'ascending' : h.column.getIsSorted() === 'desc' ? 'descending' : undefined}>
                    {h.column.getCanSort() ? (
                      <button type="button" onClick={h.column.getToggleSortingHandler()} className="flex items-center gap-1 uppercase">
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
                  <td key={cell.id} className="px-3 py-2">{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
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

function Clamp({ text }: { text: string | null | undefined }) {
  if (!text) return null
  return <span className="line-clamp-3 max-w-xs text-[var(--muted)]" title={text}>{text}</span>
}
