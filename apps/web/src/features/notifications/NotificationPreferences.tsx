import { useEffect, useState } from 'react'
import { ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { browserPermission, requestBrowserPermission, type BrowserPermissionState } from './browserNotifications'
import { useNotificationPreferences, useUpdateNotificationPreferences } from './api'
import type { NotificationPreferences as Preferences } from './types'

const permissionText: Record<BrowserPermissionState, string> = {
  default: 'Permissão ainda não solicitada.', granted: 'Permissão concedida.',
  denied: 'Permissão negada no navegador.', unsupported: 'Este navegador não oferece notificações nativas.',
}

export default function NotificationPreferences() {
  const query = useNotificationPreferences()
  const update = useUpdateNotificationPreferences()
  const [form, setForm] = useState<Preferences | null>(null)
  const [permission, setPermission] = useState<BrowserPermissionState>(browserPermission())
  useEffect(() => { if (query.data && !form) setForm(query.data) }, [query.data, form])
  if (query.isPending || !form) return query.isError ? <ErrorState error={query.error} onRetry={() => query.refetch()} /> : <LoadingCards count={2} height={60} />

  const toggle = (key: keyof Preferences) => setForm({ ...form, [key]: !form[key] })
  async function enableBrowser() {
    if (permission === 'denied' || permission === 'unsupported') return
    const next = await requestBrowserPermission()
    setPermission(next)
    if (next === 'granted') setForm((current) => current && { ...current, browserEnabled: true })
  }
  return (
    <form onSubmit={(event) => { event.preventDefault(); update.mutate(form) }}>
      <p className="settings-note">A caixa de entrada fica no Finora. Alertas do navegador exigem permissão e funcionam somente enquanto o Finora estiver aberto. Nenhum e-mail é enviado e push em segundo plano não faz parte desta etapa.</p>
      <div className="notification-preference-grid">
        {([
          ['enabled', 'Ativar notificações no Finora'],
          ['recurringDueEnabled', 'Vencimentos recorrentes'],
          ['invoiceDueEnabled', 'Vencimentos de faturas'],
          ['executionFailureEnabled', 'Falhas de execução'],
          ['cashRiskEnabled', 'Risco de caixa projetado'],
          ['browserShowAmounts', 'Mostrar valores nos alertas do navegador'],
        ] as Array<[keyof Preferences, string]>).map(([key, label]) => (
          <label key={key} className="notification-check"><input type="checkbox" checked={Boolean(form[key])} onChange={() => toggle(key)} /> {label}</label>
        ))}
        <label>Dias de antecedência <input className="input" type="number" min={1} max={14} value={form.upcomingLeadDays} onChange={(e) => setForm({ ...form, upcomingLeadDays: Number(e.target.value) })} /></label>
        <label>Severidade mínima no navegador <select className="input" value={form.browserMinimumSeverity} onChange={(e) => setForm({ ...form, browserMinimumSeverity: e.target.value as Preferences['browserMinimumSeverity'] })}><option value="INFO">Informativa</option><option value="WARNING">Atenção</option><option value="CRITICAL">Crítica</option></select></label>
      </div>
      <div className="browser-permission"><p><strong>Permissão do navegador:</strong> {permissionText[permission]}</p>
        {!form.browserEnabled && permission !== 'denied' && permission !== 'unsupported' && <button type="button" className="btn btn-secondary" onClick={enableBrowser}>Permitir alertas neste navegador</button>}
        {form.browserEnabled && <button type="button" className="btn btn-secondary" onClick={() => setForm({ ...form, browserEnabled: false })}>Desativar alertas do navegador</button>}
      </div>
      {update.error && <p role="alert" className="field-error">{errorMessage(update.error)}</p>}
      {update.isSuccess && <p role="status" className="settings-saved">Preferências de notificação salvas.</p>}
      <button className="btn btn-primary" disabled={update.isPending || form.upcomingLeadDays < 1 || form.upcomingLeadDays > 14}>Salvar notificações</button>
    </form>
  )
}
