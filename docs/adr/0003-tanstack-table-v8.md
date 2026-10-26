# 0003 TanStack Table v8

Date: 2026-10-26. Status: accepted.

`npm install @tanstack/react-table` now installs v9, whose API differs (`createCoreRowModel`,
no `useReactTable` export). The results table is written against v8 (`^8.21`), which is
maintained and which this code uses (`getCoreRowModel`, `getSortedRowModel`, `getFilteredRowModel`).
Moving to v9 is a contained change in `ResultsTable.tsx` when it is wanted.
