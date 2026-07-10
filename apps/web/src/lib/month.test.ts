import { describe, expect, it } from 'vitest'
import { addMonths } from './month'

describe('addMonths', () => {
  it('moves forward across year boundaries', () => {
    expect(addMonths('2026-12', 1)).toBe('2027-01')
  })

  it('moves backward across year boundaries', () => {
    expect(addMonths('2026-01', -1)).toBe('2025-12')
  })

  it('handles multi-month jumps', () => {
    expect(addMonths('2026-07', 6)).toBe('2027-01')
    expect(addMonths('2026-07', -7)).toBe('2025-12')
  })
})
