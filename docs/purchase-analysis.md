# Análise de compra

`GET /api/wishlist/{id}/analysis` compara as opções de compra de um item usando
apenas dados do Finora e as premissas configuráveis. Todo o cálculo é
determinístico, em `BigDecimal`, implementado em `PurchaseAnalysisService` e
coberto por testes de cenário (`PurchaseAnalysisEngineTest`).

## Entradas

**Contexto financeiro** (`FinancialContextService`, janela dos **3 meses
completos** anteriores à data de referência — o mês corrente parcial é excluído):

- `availableCash` — soma dos saldos derivados das contas não arquivadas;
- `avgMonthlyIncome` / `avgMonthlyExpense` — somas da janela divididas pelo
  número de meses **que tiveram transações** (não pela janela cheia); `null`
  sem histórico;
- `avgMonthlySurplus` = renda média − despesa média (`null` sem histórico);
- `monthlyCommitments` — recorrentes ativos com ocorrência no próximo mês;
- `historyMonthsUsed` — quantos meses da janela tinham dados (exposto na resposta).

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

## Limitações conhecidas

- A janela de 3 meses é uma média simples; meses atípicos distorcem a projeção.
- Parcelas futuras não são reconciliadas contra orçamentos por categoria.
- A taxa de oportunidade é informada pelo usuário — o Finora não assume
  rendimento de investimento por conta própria.
- Projeção ≠ garantia: a linguagem do produto trata tudo como estimativa
  baseada nos dados atuais.
