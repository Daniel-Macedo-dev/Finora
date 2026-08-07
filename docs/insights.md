# Insights

`GET /api/insights?month=YYYY-MM` devolve as observações determinísticas do mês.
Toda regra só dispara quando os dados que a justificam existem — e, desde o
multi-moeda, só quando esses dados são **comparáveis entre si**.

## Duas famílias de regra

Elas falham de maneiras diferentes, e confundir isso é o erro que este documento
existe para evitar.

**Nativas do recurso.** Todos os operandos pertencem a um cartão e compartilham a
moeda dele, então a conclusão é verdadeira independentemente do resto do razão.
Nunca são suprimidas e são enunciadas na moeda do próprio cartão.

| Regra | Valor | Moeda |
| --- | --- | --- |
| `INVOICE_OVERDUE` | saldo em aberto da fatura | moeda do cartão |
| `INVOICE_DUE_SOON` | saldo em aberto da fatura | moeda do cartão |
| `CARD_UTILIZATION_HIGH` | limite disponível | moeda do cartão |

A porcentagem de utilização é adimensional: é a razão entre dois valores do mesmo
cartão, então continua válida em qualquer moeda.

**Agregadas em moeda base.** Comparam ou dividem valores vindos de todo o razão,
então só significam alguma coisa quando esses valores já estão na moeda base.

| Regra | Exige completude em moeda base de | Valor | Moeda |
| --- | --- | --- | --- |
| `EXPENSE_INCREASE` | despesas do mês **e** do mês anterior | diferença | base |
| `CATEGORY_DOMINANT` | despesas do mês | valor da categoria | base |
| `BUDGET_EXCEEDED` | o próprio orçamento (status ≠ `INCOMPLETE`) | excedente | base |
| `BUDGET_NEAR_LIMIT` | o próprio orçamento | restante | base |
| `COMMITMENT_SHARE_HIGH` | renda média **e** compromissos | compromissos | base |
| `CARD_INSTALLMENT_BURDEN_HIGH` | renda média **e** parcelas do próximo mês | parcelas | base |
| `GOAL_OFF_PACE` | sobra média **e** moeda da meta = base | aporte sugerido | base |
| `WISHLIST_AFFORDABLE` | caixa disponível **e** moeda do item = base | opção à vista mais barata | base |

Uma regra agregada incompleta nunca é aproximada. Uma despesa em dólares e outra
em reais não produzem uma porcentagem de crescimento aproximada — produzem um
número sem significado, que apareceria como manchete.

### Por que a dominância exige o mês inteiro

`CATEGORY_DOMINANT` afirma que uma categoria concentra os gastos **do mês**. Com
gastos estrangeiros presentes, o denominador é desconhecido sem cotação; usar só
a parte em moeda base inflaria a fatia de toda categoria base. A regra é
suprimida, não recalculada sobre um universo menor.

### Orçamentos incompletos

`BudgetStatus.INCOMPLETE` existe justamente para que um orçamento cuja categoria
tem gastos em outra moeda nunca seja chamado de estourado, de saudável ou de
qualquer coisa, a partir da parte que por acaso está em reais. Esses orçamentos
não geram insight; entram na cobertura.

## Contrato

```
InsightsResponse:
  month
  baseCurrency          moeda em que toda conclusão agregada é enunciada
  insights[]
  aggregateCoverage

Insight:
  type severity title message
  amount    valor principal, ou null
  currency  moeda de amount, null exatamente quando amount é null

AggregateCoverage:
  complete            nenhuma regra agregada foi retida
  missingCurrencies   moedas que efetivamente barraram alguma regra
  unavailableRules    identificadores estáveis, nunca prosa
```

O par valor/moeda é garantido pelo construtor, não por convenção: as duas metades
são produzidas em lugares diferentes — uma regra nativa pega a moeda do cartão,
uma agregada a moeda base — e um valor que perdeu a denominação no caminho não é
uma informação menor, é **outro número**.

`unavailableRules` usa os códigos do enum `InsightRule`. Eles são contrato de
máquina: a interface os traduz em frases e nunca os mostra.

## Ausência de dado não é ausência de cotação

`aggregateCoverage` reporta uma regra **apenas** quando ela tinha entrada
relevante e a incompatibilidade de moeda impediu a avaliação. Silêncio comum
continua silêncio comum:

| Situação | Insight | Cobertura |
| --- | --- | --- |
| Sem mês anterior | nenhum `EXPENSE_INCREASE` | nada |
| Sem histórico de renda | nenhuma razão sobre renda | nada |
| Sem meta em andamento | nenhum `GOAL_OFF_PACE` | nada |
| Sem item candidato | nenhum `WISHLIST_AFFORDABLE` | nada |
| Despesa mista no mês | nenhum crescimento nem dominância | as duas regras |
| Caixa misto com item candidato | nenhuma viabilidade | `WISHLIST_AFFORDABLE` |
| Meta em dólar, sobra em real | nenhum ritmo | `GOAL_OFF_PACE` |

Uma conta vazia não pode parecer quebrada.

`missingCurrencies` também é estreito: só entra a moeda que barrou alguma regra.
Quem apenas possui um cartão em dólar, enquanto todo agregado rodou bem em reais,
é informado de que nada foi retido — porque nada foi.

## Contexto financeiro

As regras agregadas leem `FinancialContextService`, o **mesmo** que a análise de
compra usa. Não existe segunda implementação: um ajuste semântico — o que conta
como caixa disponível, como um denominador é escolhido, quando uma dimensão está
completa em moeda base — vale para os dois motores. O contexto escalar que somava
denominações foi removido, não deixado como fallback.

Ver [purchase-analysis.md](purchase-analysis.md) para as garantias do contexto e
[multi-currency-core.md](multi-currency-core.md) para o modelo de moedas.

## Custo

Uma requisição de insights faz, além do contexto: **uma** consulta agrupada
cobrindo os dois meses por tipo e moeda, e **uma** consulta de categorias por
moeda. Nenhuma consulta por moeda, por categoria, por meta ou por item.

O acesso por cartão (limite e faturas) mantém o padrão que já existia antes desta
fase e não foi alterado aqui.

## Limitações conhecidas

- **Não existe cotação.** Nenhuma regra agregada é estimada, aproximada ou
  convertida; ela simplesmente não é enunciada.
- A definição de despesa das regras não mudou: continuam sendo lançamentos
  financeiramente ativos, sem reconhecimento de parcela de cartão.
- A janela de histórico é a mesma do contexto (3 meses completos anteriores).
