import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export interface SavedLogin {
  host: string
  username: string
  createdAt: string
  lastUsedAt: string | null
}

export const useLogins = () => useQuery({ queryKey: ['logins'], queryFn: () => api<SavedLogin[]>('/api/logins') })

export function useSaveLogin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { host: string; username: string; password: string }) => api<SavedLogin>('/api/logins', { method: 'PUT', body }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['logins'] })
      void client.invalidateQueries({ queryKey: ['apply', 'status'] })
    },
  })
}

export function useDeleteLogin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (host: string) => api<void>(`/api/logins/${encodeURIComponent(host)}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['logins'] }),
  })
}
