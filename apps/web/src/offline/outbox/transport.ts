import { api } from '../../lib/api'
import type { SendBatch, WireEnvelope, WireResult } from './replay'

/**
 * The one route queued mutations travel over.
 *
 * It goes through the central API client like everything else, which means it
 * inherits CSRF and the session cookie — and, importantly, inherits the
 * offline guard too: the client refuses unsafe requests while the vault is
 * unlocked offline, so a replay can only ever leave once the app is genuinely
 * back online.
 */
export const OFFLINE_SYNC_PATH = '/offline-sync/mutations'

interface BatchResponse {
  results: WireResult[]
}

export const sendMutations: SendBatch = async (envelopes: WireEnvelope[]) => {
  const response = await api.post<BatchResponse>(OFFLINE_SYNC_PATH, { mutations: envelopes })
  return response.results
}
