# Previsão de caixa (forecast)

Previsão **determinística** de movimentação futura de dinheiro. O forecast
modela *movimento de caixa*, não reconhecimento de despesa — a distinção que o
domínio de cartões estabeleceu permanece:

```text
parcela de cartão   = reconhecimento de despesa no mês da fatura
pagamento de fatura = redução de caixa bancário, nunca uma segunda despesa
```

## Entradas (combinadas sem contagem dupla)

1. **Saldo de abertura** — saldos derivados das contas ativas até hoje
   (transações + pagamentos de fatura liquidados).
2. **Transações futuras reais** — lançamentos já registrados com data após
   hoje (`ACTUAL_TRANSACTION`). Transações **financeiramente inativas**
   (crédito legado com conversão ativa) ficam de fora em todas as camadas —
   saldo de abertura inclusive: a despesa vira caixa apenas pela fatura da
   compra gerada, uma única vez
   ([legacy-credit-conversion.md](legacy-credit-conversion.md)).
3. **Ocorrências recorrentes de conta não materializadas**
   (`RECURRING_ACCOUNT_OCCURRENCE`) — janela `(hoje, fim]`:
   - materializada → aparece pelo artefato real, nunca pela projeção;
   - pulada ou estornada → excluída (não é reintroduzida silenciosamente);
   - reagendada → aparece na data efetiva;
   - com falha → continua esperada (o valor ainda deve acontecer).
4. **Faturas de cartão em aberto** (`CARD_INVOICE`) — o saldo em aberto
   (parcelas + ajustes − pagamentos liquidados) reduz caixa **na data de
   vencimento da fatura**; fatura vencida e em aberto é pagável imediatamente
   (impacta caixa hoje). Pagamentos estornados restauram a projeção.
5. **Compras recorrentes de cartão projetadas**
   (`PROJECTED_RECURRING_CARD_PURCHASE`) — cada compra futura é dividida pelo
   **alocador real de parcelas** e colocada nas datas de vencimento do **ciclo
   real de fatura**. A compra em si nunca subtrai caixa na data da compra.
   Linhas de fatura existentes contêm apenas cobranças materializadas, então
   as duas fontes nunca se sobrepõem.

## Exclusões deliberadas

Itens de desejo, limites de orçamento, metas e sugestões da análise de compra
são **intenções ou limites**, não eventos de caixa agendados — nunca entram.
Nenhum modelo probabilístico ou pontuação opaca: toda linha é explicável por
uma fonte determinística (`source` estável em cada evento).

## Atribuição de conta e fluxos "sem conta"

- Saída de fatura usa a **conta padrão de pagamento** do cartão quando
  configurada (e não arquivada); caso contrário o fluxo é **sem conta**
  (`unassigned`) — nunca se escolhe outra conta arbitrariamente.
- Recorrente somente-planejamento (sem destino) é sempre sem conta.
- Fluxos sem conta são divulgados separadamente
  (`unassignedInflows`/`unassignedOutflows`) e **nunca alteram um saldo** —
  aparecem na lista de eventos com a marcação "sem conta definida".

## Saída

`GET /api/forecast?days&accountId` (padrão 90 dias, máximo 730 / 24 meses;
filtro opcional por conta ativa do usuário):

- saldo de abertura, entradas projetadas, saídas de conta, saídas de fatura,
  saldo de fechamento;
- **menor saldo projetado** e sua data;
- **primeira data de saldo negativo** (`firstNegativeDate`, `null` se nunca);
- fluxos sem conta (entradas/saídas separadas);
- eventos diários ordenados, cada um com fonte, conta, vínculos (transação,
  recorrente, fatura, cartão) e `balanceAfter` (saldo após o evento — `null`
  em eventos sem conta, que não movem o saldo);
- resumo mensal (entradas, saídas, líquido, saldo ao fim do mês).

## Frontend

- **Página Previsão** (`/forecast`): seletor de horizonte (30/90/180/365 dias),
  filtro por conta, KPIs, alerta de saldo negativo, aviso de fluxos sem conta,
  gráfico de saldo (Recharts, com contexto textual acessível e leitura em modo
  escuro), resumo mensal e linha do tempo de eventos com links para transação,
  recorrente, cartão e fatura.
- **Dashboard**: seção compacta "Caixa futuro (30 dias)" — saldo projetado,
  próximo recorrente, próxima obrigação de fatura, alerta de primeira data
  negativa e contagem de recorrências com falha. Usa o serviço de forecast
  como fonte única — o frontend não calcula projeção financeira própria.

## Limitações conhecidas

- O forecast considera apenas contas não arquivadas do usuário.
- Não há intervalo de confiança nem cenários — uma única projeção
  determinística baseada nos dados atuais.
- Compras avulsas futuras de cartão ainda não registradas obviamente não
  aparecem; apenas faturas existentes e recorrentes projetados.

## Uso pela central de notificações

O feed `GET /api/events/due` mantém seu `id` compatível e expõe também
`sourceKey`, estável entre próximo/hoje/vencido. O sincronizador reutiliza o
mesmo `DueEventService` para um usuário confiável, sem duplicar cálculos
financeiros. Consulte [notifications.md](notifications.md).
