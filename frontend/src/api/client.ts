export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/**
 * Calls the Spring API. Paths start with /api (proxied by Vite in development). A plain object body
 * is sent as JSON, FormData and strings as they are; 204 responses resolve to undefined.
 */
export async function api<T>(path: string, init: Omit<RequestInit, 'body'> & { body?: unknown } = {}): Promise<T> {
  const { body, ...rest } = init
  const headers: Record<string, string> = { Accept: 'application/json' }
  let payload: BodyInit | undefined
  if (body instanceof FormData || typeof body === 'string') {
    payload = body
  } else if (body !== undefined) {
    payload = JSON.stringify(body)
    headers['Content-Type'] = 'application/json'
  }
  const response = await fetch(path, { ...rest, body: payload, headers: { ...headers, ...rest.headers } })
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`
    try {
      const json = (await response.json()) as { error?: string }
      if (json.error) detail = json.error
    } catch {
      // not JSON, keep the status line
    }
    throw new ApiError(response.status, detail)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}
