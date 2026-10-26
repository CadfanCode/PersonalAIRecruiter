import { vi } from 'vitest'

export interface Call {
  method: string
  url: string
  body: unknown
}

type Handler = (call: Call) => unknown

/**
 * Stubs fetch with a tiny router: keys are "METHOD /path". A handler returns the JSON body,
 * `null` for 204, or a ready-made Response for error cases. Unknown routes fail the test loudly.
 */
export function mockApi(routes: Record<string, Handler | unknown>) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      let body: unknown = init?.body
      if (typeof body === 'string') {
        try {
          body = JSON.parse(body)
        } catch {
          // plain text body (seed file)
        }
      }
      const call = { method, url, body }
      calls.push(call)
      const route = routes[`${method} ${url}`]
      if (route === undefined) throw new Error(`Unmocked request: ${method} ${url}`)
      const result = typeof route === 'function' ? (route as Handler)(call) : route
      if (result instanceof Response) return result
      if (result === null) return new Response(null, { status: 204 })
      return new Response(JSON.stringify(result), { status: 200 })
    }),
  )
  return calls
}
