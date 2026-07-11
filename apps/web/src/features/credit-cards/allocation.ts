/**
 * Frontend mirror of the backend's deterministic installment allocation, used
 * only for the live preview in the purchase form. The backend remains the
 * authority — after submission the UI always shows the server-generated
 * schedule.
 *
 * Rule (identical to the API): normalize to cents, give every installment the
 * integer base, and add the remainder one cent each to the LAST installments.
 */
export function previewInstallments(total: number, count: number): number[] | null {
  if (!Number.isFinite(total) || total <= 0 || !Number.isInteger(count) || count < 1) {
    return null
  }
  const cents = Math.round(total * 100)
  if (cents < count) {
    return null
  }
  const base = Math.floor(cents / count)
  const remainder = cents % count
  return Array.from({ length: count }, (_, index) =>
    (index >= count - remainder ? base + 1 : base) / 100,
  )
}
