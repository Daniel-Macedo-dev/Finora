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

### Partição por moeda

Um saldo corrente só significa alguma coisa em **uma** denominação. Somar reais
com dólares num único saldo projetado seria o número mais acionável — e mais
errado — que o produto poderia mostrar a alguém decidindo se pode gastar.

Por isso a previsão roda **uma corrida independente por moeda**, numa única
passagem ordenada. Cada moeda tem seu próprio saldo de abertura, sua própria
série, seu próprio menor saldo e sua própria primeira data negativa. Um saldo
negativo em USD nunca marca o saldo em BRL.

`byCurrency` é sempre a resposta autoritativa. Cada entrada traz:

- `currency`;
- `openingBalance`;
- `income`;
- `accountExpenses`;
- `invoiceOutflows`;
- `closingBalance`;
- `lowestBalance` e `lowestBalanceDate`;
- `firstNegativeDate` (`null` se nunca);
- `unassignedInflows` / `unassignedOutflows`;
- `assignedEventCount`;
- `months` (entradas, saídas, líquido, saldo ao fim do mês).

### Escalares legados

Os campos escalares ao lado (`openingBalance`, `closingBalance`,
`lowestBalance`, `firstNegativeDate`, `projectedIncome`, …) são o contrato
anterior ao multi-moeda, mantidos para clientes e cópias offline que já têm esse
formato. Eles são preenchidos **apenas quando a previsão é homogênea**, com
`currency` nomeando a denominação. Uma previsão mista deixa todos eles nulos —
nunca envia um valor misto só para manter um campo não-nulo.

Uma previsão **filtrada por conta** é homogênea por construção: ela contém
exatamente os eventos que liquidam na moeda daquela conta, então todos os
escalares continuam disponíveis, em `currency`.

### Moeda de cada evento

Cada evento carrega a moeda derivada do **recurso de origem**, nunca da
requisição:

| Origem | Moeda |
| --- | --- |
| lançamento com conta | moeda do lançamento (FK garante que é a da conta) |
| lançamento sem conta | moeda do próprio lançamento |
| ocorrência recorrente de conta | moeda do compromisso |
| compromisso só de projeção | moeda do compromisso |
| fatura de cartão | moeda do cartão |
| compra recorrente projetada | moeda do cartão |

`balanceAfter` é o saldo **daquela moeda** depois do evento.

### Saída

- eventos diários ordenados, cada um com fonte, moeda, conta, vínculos
  (transação, recorrente, fatura, cartão) e `balanceAfter` (`null` em eventos
  sem conta, que não movem saldo nenhum);
- `byCurrency` com um resumo completo por moeda;
- escalares legados quando homogêneo.

## Frontend

- **Página Previsão** (`/forecast`): seletor de horizonte (30/90/180/365 dias),
  filtro por conta (que mostra a moeda de cada conta), e então **um bloco por
  moeda** — KPIs, alerta de saldo negativo, aviso de fluxos sem conta, gráfico
  de saldo (Recharts, um eixo por denominação, com contexto textual acessível e
  leitura em modo escuro) e resumo mensal. Com uma única moeda o layout é o de
  sempre, sem título extra. Com mais de uma, cada bloco recebe um título com o
  nome da moeda e uma nota explicando que os valores não são somados entre
  moedas. A linha do tempo de eventos é única e ordenada, cada linha com sua
  própria moeda e o saldo daquela moeda.
- **Dashboard**: seção compacta "Caixa futuro (30 dias)" — **um saldo projetado
  por moeda**, próximo recorrente, próxima obrigação de fatura, um alerta de
  primeira data negativa por moeda afetada e contagem de recorrências com falha.
  Usa o serviço de forecast como fonte única — o frontend não calcula projeção
  financeira própria, e não existe saldo consolidado.

## Limitações conhecidas

- O forecast considera apenas contas não arquivadas do usuário.
- Não há intervalo de confiança nem cenários — uma única projeção
  determinística baseada nos dados atuais.
- Compras avulsas futuras de cartão ainda não registradas obviamente não
  aparecem; apenas faturas existentes e recorrentes projetados.
- **Não existe saldo consolidado entre moedas.** Isso exigiria cotações, que o
  Finora deliberadamente ainda não tem. Uma moeda com saldo de abertura zero e
  nenhum evento não gera série, para que um usuário só em BRL continue vendo
  exatamente uma.

## Uso pela central de notificações

O feed `GET /api/events/due` mantém seu `id` compatível e expõe também
`sourceKey`, estável entre próximo/hoje/vencido. O sincronizador reutiliza o
mesmo `DueEventService` para um usuário confiável, sem duplicar cálculos
financeiros. Consulte [notifications.md](notifications.md).
