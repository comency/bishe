import { describe, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useAiRequest, validAiResult, type AiResult } from '../src/lib/ai'
import { safeReturnTo } from '../src/router'
const generated: AiResult = { content: 'Synthetic suggestion', status: 'GENERATED', reason: null }
function fixture() {
  const scope = effectScope(), input = ref('Synthetic original')
  const client = vi.fn<(...args: unknown[]) => Promise<unknown>>()
  const state = scope.run(() => useAiRequest('polish', () => input.value, client as <T>() => Promise<T>))!
  return { scope, input, client, state }
}
describe('AI preview isolation', () => {
  it.each([null, {}, { content: 'x', status: 'GENERATED', reason: 'DISABLED' }, { content: '', status: 'GENERATED', reason: null }, { content: 'x', status: 'UNAVAILABLE', reason: null }])('rejects malformed status combination %j', result => expect(validAiResult(result)).toBe(false))
  it('disabled cannot be adopted and never changes input', async () => { const f = fixture(); f.client.mockResolvedValue({ content: 'Disabled', status: 'UNAVAILABLE', reason: 'DISABLED' }); await f.state.run(); expect(f.state.canApply.value).toBe(false); expect(f.input.value).toBe('Synthetic original'); f.scope.stop() })
  it('generated output requires explicit adoption', async () => { const f = fixture(); f.client.mockResolvedValue(generated); await f.state.run(); expect(f.state.canApply.value).toBe(true); expect(f.input.value).toBe('Synthetic original'); f.scope.stop() })
  it('changed input makes old preview unadoptable', async () => { const f = fixture(); let resolve!: (value: unknown) => void; f.client.mockImplementation(() => new Promise(r => { resolve = r })); const task = f.state.run(); f.input.value = 'Newer content'; resolve(generated); await task; expect(f.state.canApply.value).toBe(false); expect(f.input.value).toBe('Newer content'); f.scope.stop() })
  it('disposed component ignores late responses', async () => { const f = fixture(); let resolve!: (value: unknown) => void; f.client.mockImplementation(() => new Promise(r => { resolve = r })); const task = f.state.run(); f.scope.stop(); resolve(generated); await task; expect(f.state.result.value).toBeNull(); expect(f.state.snapshot.value).toBe('') })
  it('newer request wins even when abort is ignored by transport', async () => { const f = fixture(); let resolve!: (value: unknown) => void; f.client.mockImplementationOnce(() => new Promise(r => { resolve = r })).mockResolvedValueOnce({ ...generated, content: 'Latest' }); const task = f.state.run(); f.input.value = 'Latest input'; await f.state.run(); resolve(generated); await task; expect(f.state.result.value?.content).toBe('Latest'); f.scope.stop() })
  it('provider error preserves input and exposes no adoption', async () => { const f = fixture(); f.client.mockRejectedValue(new Error('Unavailable')); await f.state.run(); expect(f.state.error.value).toBe('Unavailable'); expect(f.state.canApply.value).toBe(false); expect(f.input.value).toBe('Synthetic original'); f.scope.stop() })
  it('oversized polish is never adoptable', async () => { const f = fixture(); f.client.mockResolvedValue({ ...generated, content: 'x'.repeat(3001) }); await f.state.run(); expect(f.state.canApply.value).toBe(false); f.scope.stop() })
  it('assistant is a valid login return destination', () => expect(safeReturnTo('/assistant')).toBe('/assistant'))
})
