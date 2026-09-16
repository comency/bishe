import { describe, expect, it } from 'vitest'
import config from '../vite.config'

describe('isolated local server targets', () => {
  it.each([
    ['development', 5174, 8080],
    ['integration', 15174, 18080],
    ['modeltrial', 15176, 18081],
  ])('keeps %s on its own loopback endpoints', async (mode, port, backend) => {
    if (typeof config !== 'function') throw new Error('Expected mode-aware config')
    const resolved = await config({ mode: String(mode), command: 'serve' })
    expect([5173, 15175]).not.toContain(resolved.server?.port)
    expect(resolved.server).toMatchObject({
      host: '127.0.0.1', port, strictPort: true,
      proxy: { '/api': { target: `http://127.0.0.1:${backend}` } },
    })
  })
})
