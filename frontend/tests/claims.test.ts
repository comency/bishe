import { describe, expect, it } from 'vitest'
import { canCancel, singleConfirmed, type ClaimSummary } from '../src/lib/claims'
import { safeReturnTo } from '../src/router'
const claim: ClaimSummary = { id: 1, item: { itemId: 2, title: 'Synthetic', type: 'FOUND', contentVersion: 1 }, publisherId: 3, applicantId: 4, status: 'APPLIED', version: 0, createdAt: '', acceptedAt: null, handedOverAt: null, receivedAt: null, completedAt: null, endReason: null, canConfirm: false }
describe('claim actions', () => {
  it('only applicant can cancel an unprocessed claim', () => { expect(canCancel(claim, 3)).toBe(false); expect(canCancel(claim, 4)).toBe(true); expect(canCancel(claim, 5)).toBe(false) })
  it('both participants can cancel accepted zero-confirmation claims', () => { expect(canCancel({ ...claim, status: 'ACCEPTED' }, 3)).toBe(true); expect(canCancel({ ...claim, status: 'ACCEPTED' }, 4)).toBe(true) })
  it.each(['handedOverAt', 'receivedAt'])('never permits cancellation after %s', key => { const r = { ...claim, status: 'ACCEPTED' as const, [key]: '2026-09-16T00:00:00Z' }; expect(singleConfirmed(r)).toBe(true); expect(canCancel(r, 3)).toBe(false); expect(canCancel(r, 4)).toBe(false) })
  it.each(['CANCELLED', 'REJECTED', 'COMPLETED'] as const)('ended %s cannot cancel', status => { expect(canCancel({ ...claim, status }, 4)).toBe(false) })
  it.each(['/claims/mine', '/claims/incoming?itemId=2', '/claims/10', '/admin/claims', '/admin/claims/10', '/admin/logs'])('permits implemented claim route %s', path => expect(safeReturnTo(path)).toBe(path))
  it('does not permit action or unknown routes as return targets', () => { expect(safeReturnTo('/claims/10/accept')).toBe('/items'); expect(safeReturnTo('/admin/logs/10')).toBe('/items') })
})
