# Análise de compra

`GET /api/wishlist/{id}/analysis` compara as opções de compra de um item usando
apenas dados do Finora e as premissas configuráveis. Todo o cálculo é
determinístico, em `BigDecimal`, implementado em `PurchaseAnalysisService` e
coberto por testes de cenário (`PurchaseAnalysisEngineTest`).

O [histórico manual de preços](wishlist-price-history.md) aparece somente como
contexto informativo. Snapshots, mínimo e alvo não entram nos cálculos abaixo.
Somente `updateLinkedOption=true` pode mudar a análise, porque atualiza a opção
atual na mesma transação.

## Entradas

**Contexto financeiro** (`PurchaseFinancialContextService`, janela dos **3 meses
completos** anteriores à data de referência — o mês corrente parcial continua
excluído). Cada dimensão é agrupada pela moeda em que os valores realmente estão
e reporta **a própria** cobertura em moeda base, porque elas falham
independentemente: o caixa pode estar todo em reais enquanto um mês do histórico
está em dólares.

- `availableCash` — saldos das contas não arquivadas, agrupados pela moeda da
  conta. Uma conta zerada em moeda estrangeira não bloqueia a completude: zero
  converte para zero sob qualquer cotação;
- `averageIncome` / `averageExpenses` — médias **por moeda**, cada uma dividida
  pelo próprio número de meses com movimento;
- `averageSurplus` — renda menos despesa, só onde as duas são comparáveis;
- `monthlyCommitments` — recorrentes ativos do próximo mês, pela moeda do
  compromisso;
- `cardOutstanding` — obrigações de cartão, com cobranças e pagamentos
  compensados **dentro** de cada moeda;
- `nextMonthCardInstallments` — parcelas da fatura do próximo mês, pela moeda do
  cartão;
- `missingCurrencies` — o que uma etapa futura de câmbio precisaria converter,
  em ordem de catálogo.

### Denominadores independentes

O divisor é por moeda. Três meses de reais e um mês de dólares dão divisor 3 ao
real e divisor 1 ao dólar. Compartilhar um divisor subestimaria os dólares em
dois terços e triplicaria os reais.

### Sem histórico não é histórico estrangeiro

São dois estados diferentes, e confundi-los é perigoso:

| Situação | `anyHistory` | `baseComplete` | Efeito |
| --- | --- | --- | --- |
| Nenhum lançamento | `false` | `true` | análise só de caixa, com os avisos de sempre |
| Só em moeda estrangeira | `true` | `false` | `EXCHANGE_RATE_REQUIRED` |
| Misto | `true` | `false` | `EXCHANGE_RATE_REQUIRED` |
| Só em moeda base | `true` | `true` | análise completa |

Um usuário genuinamente novo continua recebendo a análise de caixa que sempre
recebeu. Um usuário cujo histórico existe em outra moeda **não** — tratar isso
como ausência apresentaria um razão estrangeiro como uma ausência segura.

### Contexto legado

`FinancialContext` e `FinancialContextService` continuam existindo **apenas**
porque `InsightService` ainda os consome; migrá-lo é uma tarefa separada. Nenhum
consumidor novo deve depender deles, e eles serão removidos quando os insights
migrarem. Os dois caminhos não se chamam: delegar de um para o outro traria a
aritmética escalar misturada de volta.

**Premissas** (`app_settings`): reserva mínima, teto de comprometimento da renda,
taxa de oportunidade mensal e limiar de orçamento (ver `domain-model.md`).

## Cálculo por opção

```
nominalCost   = preço + frete + taxas
upfrontCost   = nominalCost                  (CASH)
              = frete + taxas                (INSTALLMENT — extras à vista)
monthlyBurden = valor da parcela             (INSTALLMENT; null para CASH)

presentValue (CASH ou taxa = 0) = nominalCost
presentValue (INSTALLMENT, taxa r > 0):
    PV = frete + taxas + Σ_{k=1..n} parcela / (1 + r)^k
```

O fator de desconto é calculado iterativamente com escala 10 e HALF_UP; o
resultado final é normalizado para 2 casas (HALF_UP). Com `r = 0` a comparação
degrada limpa para o custo nominal.

`cashAfterPurchase = availableCash − upfrontCost`.

## Regras de segurança (bloqueantes)

| Código | Condição |
| --- | --- |
| `BUFFER_VIOLATION` | `cashAfterPurchase < minimum_cash_buffer` |
| `INSTALLMENT_EXCEEDS_SURPLUS` | parcela > sobra média mensal (quando conhecida) |
| `INSTALLMENT_PRESSURE_HIGH` | (parcela + recorrentes) ÷ renda média > teto configurado |

Sem histórico, as verificações de sobra/renda **não bloqueiam**: viram avisos
não bloqueantes (`INSUFFICIENT_SURPLUS_HISTORY`, `INSUFFICIENT_INCOME_HISTORY`)
— a análise nunca finge certeza que os dados não sustentam.

## Recomendação

1. **Alguma opção segura** → vence o **menor valor presente**; empates: menor
   custo nominal, depois `CASH` antes de `INSTALLMENT`, depois menor id.
   Tipo `BUY_CASH` ou `BUY_INSTALLMENT`, com `reasonCodes` como
   `LOWEST_PRESENT_VALUE`, `NOMINAL_COMPARISON` (taxa 0),
   `CASH_DISCOUNT_WORTH_IT`, `INSTALLMENTS_BEAT_CASH_AT_RATE`,
   `PRESERVES_LIQUIDITY`.
2. **Nenhuma opção segura** → `WAIT`, com:
   - `requiredAdditionalCash` = menor `(upfront + reserva − caixa)` positivo;
   - `estimatedMonthsToAfford` = teto de `requiredAdditionalCash ÷ sobra média`
     (apenas com sobra positiva conhecida; caso contrário null + aviso).
3. **Sem opções** → `NO_OPTIONS`.

A resposta expõe `assumptions` completos, análise por opção (custos, PV, caixa
após compra, issues) e explicação em pt-BR — a UI apenas apresenta.

## Disponibilidade da análise

A resposta carrega um estado explícito:

- `AVAILABLE` — todos os operandos estão na mesma moeda; premissas, opções e
  recomendação presentes, exatamente como antes;
- `EXCHANGE_RATE_REQUIRED` — a análise precisaria de cotação; **sem** premissas,
  **sem** análises de opção e **sem** recomendação.

`EXCHANGE_RATE_REQUIRED` é **HTTP 200**, não um erro. A requisição funcionou, o
item é perfeitamente legível e suas opções e histórico continuam acessíveis pelos
endpoints normais. O que falta é uma cotação. Devolver `WAIT` no lugar seria pior
que devolver nada: leria como conselho financeiro.

### Elegibilidade antes da aritmética

A elegibilidade é decidida **antes** da primeira subtração, razão ou comparação
de valor presente. Calcular valores mistos e escondê-los depois deixaria os
números errados a um refactor de distância de aparecerem. A projeção em moeda
base sobre a qual a aritmética opera só é construída depois que cada dimensão foi
provada homogênea.

Uma recomendação completa exige:

1. `item.currency` = moeda base;
2. caixa disponível completo em moeda base;
3. compromissos completos em moeda base;
4. parcelas de cartão do próximo mês completas em moeda base;
5. nenhuma opção ligada a cartão que fature em outra moeda;
6. histórico **ausente por completo** ou **completo em moeda base**.

Todos os motivos de bloqueio são reportados, não só o primeiro: quem tem item
estrangeiro *e* caixa misto merece ver os dois.

`cardOutstanding` **não** bloqueia. Ele aparece nas premissas, mas nenhuma regra
de recomendação divide ou subtrai com ele, então uma cobertura incompleta ali não
distorce conclusão nenhuma — e não deve suprimir uma análise válida.

O motivo `CARD_CONTEXT_INCOMPATIBLE` é defesa em profundidade: a escrita já recusa
vincular uma opção a um cartão de outra moeda (`WISHLIST_CURRENCY_MISMATCH`), e
tanto a moeda do item quanto a do cartão são imutáveis. Ele existe para dados que
cheguem por outro caminho, não para um fluxo alcançável pela API pública.

### Invariantes do contrato

O construtor canônico de `AnalysisResponse` recusa as combinações ilegais, não só
as fábricas: uma resposta indisponível com premissas, opções ou recomendação, uma
indisponível sem motivo, e uma disponível sem premissas ou sem recomendação. A
garantia é do tipo, não da disciplina de quem chama.

### Moeda das mensagens

Toda mensagem em pt-BR da análise — explicação, avisos e issues de opção — é
formatada **na moeda em que a análise está**, obtida do contexto já provado
homogêneo. A moeda base é configurável, então uma análise disponível pode ser em
dólares ou em ienes; imprimi-la com `R$` não seria um detalhe visual, seria
declarar um valor errado. Iene, sem centavos, não recebe casas decimais
inventadas.

### Sem lista de opções quando indisponível

Valor presente dentro de um item é homogêneo por construção: todas as opções, o
frete e as taxas herdam a moeda do item, e a taxa de oportunidade é adimensional.
Ainda assim a resposta indisponível **não** traz `OptionAnalysis`. Uma lista
ranqueada embaixo de um veredito indisponível lê como acessibilidade financeira.
As opções continuam visíveis onde sempre estiveram, no próprio item.

## Limitações conhecidas

- A janela de 3 meses é uma média simples; meses atípicos distorcem a projeção.
- Parcelas futuras não são reconciliadas contra orçamentos por categoria.
- A taxa de oportunidade é informada pelo usuário — o Finora não assume
  rendimento de investimento por conta própria.
- Projeção ≠ garantia: a linguagem do produto trata tudo como estimativa
  baseada nos dados atuais.
- **Não existe conversão.** Nenhuma cotação é buscada, inventada ou assumida
  como 1:1. Uma análise que precisaria converter é recusada, não estimada.
