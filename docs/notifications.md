# Entrega de notificações

## Escopo do produto

O Finora entrega alertas por dois canais: uma caixa de entrada persistente e,
quando o usuário autoriza, notificações nativas do navegador enquanto alguma
aba do Finora está aberta. Não há e-mail, SMS, Web Push, Service Worker ou
entrega com todas as abas fechadas. A caixa de entrada é sempre o fallback
durável.

Notificações são artefatos derivados. Ler, dispensar, adiar ou entregar um
alerta nunca altera transações, recorrentes, ocorrências, faturas, cartões,
contas ou cálculos de previsão.

## Fontes e identidade

O sincronizador reutiliza exclusivamente o `DueEventService`, a mesma fonte de
`GET /api/events/due`: recorrentes próximos/hoje/vencidos/com falha, faturas
próximas/hoje/vencidas e o primeiro risco de caixa negativo. O `id` público
legado continua disponível; `sourceKey` é a identidade estável de entrega:

- `COMMITMENT:{commitmentId}:{scheduledDate}`;
- `CARD_INVOICE:{invoiceId}`;
- `FORECAST:INSUFFICIENT_CASH`.

Tipo e severidade não fazem parte dessa chave. PostgreSQL garante uma única
linha por `(user_id, source_key)`.

## Revisões e ciclo de vida

Cada linha começa na revisão 1. Reaparecimento após resolução, aumento de
severidade, progressão para hoje/vencido/falha ou mudança material da data
incrementam a revisão. A nova revisão volta a ser não lida e elegível ao
navegador; uma escalada cancela o adiamento corrente. Atualizações simples de
título, valor ou rota preservam leitura, dispensa e adiamento.

Leitura, dispensa, entrega no navegador e adiamento guardam a revisão a que se
aplicam. Adiamentos aceitam apenas instantes futuros dentro de 30 dias. Uma
fonte ausente após geração completa é marcada como resolvida, sem exclusão. Se
a geração falhar, nenhuma resolução é executada. Se a fonte voltar, a mesma
linha é reativada, revisionada e preserva o histórico.

## Sincronização e agendamento

`POST /api/notifications/sync` faz catch-up explícito somente para o usuário da
sessão. O job automático percorre usuários `ACTIVE` em páginas limitadas e
isola falhas por usuário. Cada usuário ocupa uma transação própria. Um advisory
lock transacional por proprietário serializa job e sync manual; locks de linha
coordenam ações concorrentes. O lookback de vencidos é limitado a 30 dias e a
janela futura usa `upcomingLeadDays` (1–14).

Configuração:

| Variável | Padrão | Papel |
| --- | --- | --- |
| `FINORA_NOTIFICATIONS_AUTO_SYNC_ENABLED` | `true` | liga o job |
| `FINORA_NOTIFICATIONS_INITIAL_DELAY` | `PT1M` | atraso inicial |
| `FINORA_NOTIFICATIONS_SYNC_INTERVAL` | `PT15M` | intervalo |
| `FINORA_NOTIFICATIONS_USER_BATCH_SIZE` | `100` | usuários por página (máx. 500) |

## Preferências

`GET/PUT /api/notification-preferences` controla a caixa, janela futura,
famílias de fonte, navegador, severidade mínima e exposição de valores. V12
cria defaults para usuários existentes e o registro cria a linha atomicamente.
Desabilitar uma família faz o próximo sync resolver os itens fora do conjunto;
o histórico não é apagado.

Ao habilitar o navegador, o servidor registra um baseline. Revisões anteriores
ao baseline não formam um backlog inesperado. A exposição de valores começa
desligada.

## API da caixa de entrada

- `GET /api/notifications?filter&page&size`: filtros `ACTIVE`, `UNREAD`,
  `SNOOZED`, `DISMISSED`, `RESOLVED`, `ALL`; página de 1–100 itens em envelope
  estável;
- `GET /api/notifications/unread-count`;
- `POST /api/notifications/{id}/read|unread|dismiss|restore|snooze`;
- `POST /api/notifications/read-all`;
- `POST /api/notifications/browser-claims` (até 10 revisões).

Toda mutação exige sessão e CSRF. O proprietário vem da identidade autenticada;
ids de outro usuário respondem 404. `restore` remove apenas a dispensa e nunca
reativa uma fonte resolvida.

## Navegador, privacidade e navegação

A permissão da API `Notification` só é solicitada por clique explícito na tela
de configurações. A interface descreve `default`, `granted`, `denied` e
`unsupported` em português e não repete o prompt após negação.

O claim usa `FOR UPDATE SKIP LOCKED`, marca a revisão atomicamente como entregue
e impede duas abas de receberem a mesma revisão. Entrega não marca como lida.
Com `browserShowAmounts=false`, o payload omite valores. Texto é renderizado
como texto, e cliques aceitam apenas rotas internas fornecidas pelo servidor.
Uma falha da API do navegador não remove o item persistente.

## Persistência, falhas e limitações

V12 cria `notification_preferences` e `notifications`, com FKs de proprietário,
checks de enum/revisão/limites, versionamento otimista e índices alinhados às
consultas de ativo, não lido, claim e histórico resolvido. Metadados de recurso
servem somente para auditoria/navegação e não são autoridade financeira.

Foreground browser delivery é best-effort. A garantia do produto é a caixa de
entrada autenticada persistente entre reloads, sessões e reinícios.
