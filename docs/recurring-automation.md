# Automação de recorrentes

Como o Finora transforma definições recorrentes (salários, assinaturas, contas
fixas) em registros financeiros reais — de forma determinística, idempotente e
auditável.

## Definição recorrente (`commitments`)

O domínio de compromissos evoluiu para uma definição recorrente completa. Cada
definição tem:

- **Cadência** — `WEEKLY`, `MONTHLY` ou `YEARLY`:
  - *Semanal*: ancorada em `start_date`, repete a cada 7 dias. Sem deslocamento
    por fuso horário.
  - *Mensal*: usa `due_day` (1–31); dias inválidos são ajustados para o último
    dia válido do mês — 31 de janeiro → 28/29 de fevereiro → **31 de março**
    (o dia configurado é a âncora, não o resultado do mês anterior).
  - *Anual*: ancorada no mês/dia de `start_date`; 29 de fevereiro cai em 28 de
    fevereiro em anos não bissextos.
  - `start_date` e `end_date` são **inclusivos**. Definição pausada
    (`active = false`) não produz ocorrência alguma.
- **Modo de execução** (`execution_mode`) — `MANUAL` (o usuário executa cada
  ocorrência) ou `AUTOMATIC` (ocorrências vencidas viram registros reais sem
  ação manual).
- **Destino** (`target_kind`):
  - `PROJECTION_ONLY` — apenas planejamento; nada é lançado.
  - `ACCOUNT_TRANSACTION` — cada ocorrência vira uma transação real na conta
    configurada (conta do mesmo dono, não arquivada; método de pagamento
    `CREDIT` genérico é rejeitado).
  - `CREDIT_CARD_PURCHASE` — cada ocorrência vira uma compra real no cartão
    configurado (cartão do mesmo dono, ativo, categoria de despesa,
    `installment_count` entre 1 e 120).

Coerência garantida **também no banco** (`ck_commitments_target_refs`): cada
destino carrega exatamente a referência que precisa, e `AUTOMATIC` exige um
destino concreto (`ck_commitments_automatic_has_target`).

### Migração de compromissos legados (V9)

Compromissos existentes migram com comportamento preservado: `MANUAL` +
`PROJECTION_ONLY` + 1 parcela. Um compromisso legado com
`payment_method = 'CREDIT'` **nunca** vira transação de crédito genérica —
permanece somente-planejamento até o usuário atribuir um cartão real (a UI o
marca como "Crédito legado" e explica o porquê).

## Ocorrências (`commitment_occurrences`)

Registro auditável de cada instância da recorrência.

- **Identidade estável** — `UNIQUE (commitment_id, scheduled_date)`: para uma
  definição e uma data originalmente agendada existe no máximo uma identidade.
  É a âncora de idempotência de geração, materialização e catch-up.
- **Data efetiva** — reagendar muda apenas `effective_date`; a identidade
  (`scheduled_date`) nunca muda.
- **Persistência preguiçosa** — ocorrências calculadas aparecem no preview como
  virtuais (`persisted = false`); qualquer ação de ciclo de vida persiste a
  identidade primeiro. Só se cria linha para data que a recorrência realmente
  produz (`OCCURRENCE_DATE_INVALID` caso contrário).
- **Ciclo de vida** — `SCHEDULED → MATERIALIZED | SKIPPED | FAILED`;
  `MATERIALIZED → REVERSED` (terminal). `FAILED` permanece visível e
  re-executável; `SKIPPED` pode ser reativada (`unskip`).
- **Rastreabilidade** — a linha guarda o artefato gerado (`transaction_id` OU
  `card_purchase_id`, nunca ambos — `ck_..._single_artifact`), código/resumo de
  falha, `materialized_at`, `reversed_at` e se a execução foi automática.

Editar a definição afeta apenas ocorrências futuras não materializadas: linhas
persistidas cujo `scheduled_date` a nova agenda não produz mais continuam
visíveis no histórico (nunca são reescritas ou apagadas).

## Motor de materialização

Um único caminho idempotente (`OccurrenceMaterializer.attempt`) usado por
execução manual, retry, processamento automático e catch-up. O serviço é
**owner-explícito** (recebe `userId`) — o processamento em segundo plano nunca
emula uma requisição autenticada.

Sequência por tentativa, em transação própria (`REQUIRES_NEW`):

1. trava a identidade (`SELECT … FOR UPDATE`) ou a cria;
2. valida o estado (só `SCHEDULED`/`FAILED` são materializáveis);
3. valida o destino atual e cria o artefato pelas regras do domínio dono —
   transações passam pelas regras compartilhadas; compras de cartão usam
   `CardPurchaseService` (limite, trava do cartão, ciclo de fatura, alocador de
   parcelas — nada é reimplementado);
4. vincula o artefato e marca `MATERIALIZED`, atomicamente.

Falha de negócio (ex.: limite insuficiente) desfaz **tudo** da tentativa — sem
compra órfã, sem parcela parcial, sem mutação de fatura — e é registrada em
transação separada como `FAILED` com código e mensagem segura (300 chars).

**Proteção contra corrida em camadas**: trava pessimista da identidade →
constraint única `(commitment_id, scheduled_date)` → índices únicos parciais
nos vínculos de artefato (`uq_..._transaction`, `uq_..._purchase`). Provado por
teste de integração com dois processadores concorrentes em banco real.

## Processamento automático e catch-up

`DueOccurrenceScheduler`: primeira execução 1 minuto após o boot (catch-up de
downtime), depois a cada 6 horas (`finora.recurring.processing-interval`).
Como as identidades derivam do **calendário** (nunca de ticks do scheduler), a
primeira execução após qualquer downtime reencontra naturalmente tudo o que
venceu offline.

- Usuários são processados independentemente; a falha de um não bloqueia outro.
- Cada ocorrência roda em transação própria — um backlog grande não segura uma
  transação gigante nem para na primeira falha.
- `POST /api/commitments/process-due` dispara o mesmo motor para o usuário
  autenticado (uso determinístico em testes e recuperação operacional). Os
  testes de integração desligam o scheduler
  (`finora.recurring.auto-processing.enabled=false`) e dirigem o processamento
  explicitamente.

## Estorno

`POST .../reverse` desfaz uma ocorrência materializada **exatamente uma vez**:

- destino conta: a transação gerada é desvinculada e apagada (saldo restaurado);
- destino cartão: usa o cancelamento do próprio domínio de cartões — fatura
  liquidada bloqueia o estorno com o erro do domínio de cartões;
- a ocorrência fica `REVERSED` (terminal): o processamento automático não a
  recria e um segundo estorno é rejeitado (`OCCURRENCE_NOT_MATERIALIZED`).

Não há orfanamento silencioso: o vínculo ocorrência↔artefato é limpo na mesma
transação do desfazimento.

## API

Todas as rotas são owner-scoped — o identificador de outro usuário se comporta
como inexistente (404).

| Rota | O que faz |
| --- | --- |
| `GET /api/commitments` | Lista definições (com destino, modo, próxima data, falhas) |
| `POST /api/commitments` · `PUT /api/commitments/{id}` | Cria/edita definição (edição não reescreve histórico) |
| `POST /api/commitments/{id}/pause` · `/resume` · `/end` | Pausa, retoma, encerra (fim = hoje) |
| `DELETE /api/commitments/{id}` | Exclui; com histórico executado, orienta pausar/encerrar |
| `GET /api/commitments/{id}/occurrences?from&to` | Preview calculado + estado persistido (máx. 24 meses) |
| `GET /api/commitments/{id}/occurrences/history` | Histórico persistido paginado |
| `POST .../occurrences/{date}/materialize` · `/retry` | Executa (retry usa o mesmo caminho) |
| `POST .../occurrences/{date}/skip` · `/unskip` | Pula / reativa |
| `POST .../occurrences/{date}/reschedule` | Move a data efetiva (identidade imutável) |
| `POST .../occurrences/{date}/reverse` | Estorna o artefato gerado |
| `POST /api/commitments/process-due` | Processa vencidos do usuário autenticado |
| `GET /api/events/due?from&to` | Eventos prontos para notificação (máx. 92 dias) |

### Eventos prontos para notificação

`GET /api/events/due` deriva (nunca persiste) eventos estáveis e deduplicáveis:
recorrente vence em breve / vence hoje / vencido sem execução / falha de
execução; fatura vence em breve / hoje / vencida; e caixa projetado
insuficiente (primeira data negativa do forecast). Cada evento carrega ID
estável, tipo, severidade, data, título, valor, recurso relacionado e rota de
ação no frontend. Este projeto **não envia** notificações — apenas prepara os
dados.

## Limitações conhecidas

- Sem fim de recorrência por "número de ocorrências" (apenas por data).
- Preview limitado a 24 meses; feed de eventos a 92 dias.
- Compromissos legados `CREDIT` migram por
  `POST /api/commitments/{id}/legacy-card-mapping`: o alvo passa a ser um
  cartão real e `automation_from` impede qualquer retroativo — ocorrências
  históricas ficam intocadas. Ver
  [legacy-credit-conversion.md](legacy-credit-conversion.md).
- O valor da definição é único — sem valores variáveis por ocorrência.
