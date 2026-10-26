export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** Fetches JSON from the Spring API. Paths start with /api (proxied by Vite in development). */
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: 'application/json', ...init?.headers },
  })
  if (!response.ok) {
    throw new ApiError(response.status, `${response.status} ${response.statusText} for ${path}`)
  }
  return (await response.json()) as T
}
