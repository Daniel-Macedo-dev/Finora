# Cartões de crédito e ciclo de fatura

O crédito é um domínio próprio (`com.finora.api.creditcard`), não uma forma de
pagamento de transação. Cartões têm limite, dia de fechamento e dia de
vencimento; compras geram parcelas atribuídas a faturas mensais; pagar a fatura
liquida caixa de uma conta — sem nunca contar a mesma despesa duas vezes.

## Invariante contábil central

> A despesa de cartão é reconhecida pelas **parcelas ativas no mês da fatura**.
> O **pagamento da fatura** reduz o saldo da conta escolhida, mas **não é uma
> nova despesa** — as parcelas que ele liquida já contaram nos seus meses.

Uma compra de R$ 1.200,00 em 12× contribui ~R$ 100,00 de despesa em cada um dos
12 meses de fatura. O pagamento de cada fatura movimenta a conta bancária uma
única vez e não toca despesa mensal, orçamento ou gráficos de categoria.

## Modelo

```
User 1──n CreditCard 0..1──1 Account (conta padrão de pagamento)
CreditCard 1──n CardInvoice           (única por usuário+cartão+mês de referência)
CreditCard 1──n CardPurchase n──1 Category(EXPENSE), 0..1 WishlistItem
CardPurchase 1──n CardInstallment n──1 CardInvoice
CardInvoice 1──n InvoicePayment n──1 Account
CardInvoice 1──n InvoiceAdjustment 0..1──1 Category
```

Tabelas: `credit_cards`, `credit_card_invoices`, `credit_card_purchases`,
`credit_card_installments`, `credit_card_payments`, `credit_card_adjustments`
(migrações V6–V8). Todas carregam `user_id` com o padrão `UNIQUE (id, user_id)`
+ FKs compostas — referência cross-owner é impossível no próprio banco.

O cartão guarda apenas nome, emissor, bandeira e **4 últimos dígitos opcionais**.
Número completo, CVV e validade não existem no modelo — o Finora não é um
processador de cartões.

## Ciclo de fatura (`InvoiceCycleCalculator`)

Autoridade única da matemática de datas; nenhum outro código deriva fechamento
ou vencimento. Uma fatura é identificada pelo **mês de referência** — o mês do
vencimento:

- `dueDate` = dia `dueDay` do mês de referência, limitado ao último dia válido
  (dia 31 em abril vira 30; em fevereiro, 28/29);
- o fechamento ocorre **no próprio mês** quando `closingDay < dueDay` e **no mês
  anterior** caso contrário;
- a compra pertence à **fatura mais próxima cujo fechamento é ≥ data da compra**
  (compra no dia do fechamento ainda entra; no dia seguinte, vai para a próxima).

As datas são **snapshots** gravados na criação da fatura: mudar os dias do
cartão só afeta faturas criadas dali em diante — histórico nunca é reescrito.

## Parcelas (`InstallmentAllocator`)

Divisão determinística e exata em centavos: normaliza o total para centavos,
divide pela quantidade e distribui o resto de 1 centavo às **últimas** parcelas.
`R$ 100,00 / 3 → 33,33 · 33,33 · 33,34`. A soma é sempre idêntica ao total —
nenhum centavo é criado ou perdido, sem ponto flutuante em lugar nenhum.

Parcelas são consecutivas a partir da primeira fatura e cada uma pertence a uma
fatura (criada sob demanda). Compra, parcelas e faturas nascem em uma única
transação, sob **lock pessimista no cartão** — duas compras concorrentes não
passam ambas pela checagem de limite desatualizada.

## Limite disponível (`CardLimitService`)

```
usedLimit      = parcelas ativas (passadas e futuras)
               + ajustes ativos líquidos (débitos − créditos)
               − pagamentos concluídos e não estornados
availableLimit = creditLimit − usedLimit
```

Toda parcela não paga consome limite desde a compra; pagamento devolve;
cancelamento libera. Compra que excede o disponível é rejeitada
(`INSUFFICIENT_CARD_LIMIT`).

## Status da fatura

Sempre **derivado** (data atual + datas snapshot + totais derivados), nunca
armazenado — não há scheduler. Precedência:

1. `PAID` — em aberto zerado;
2. `OVERDUE` — venceu com saldo;
3. `PARTIALLY_PAID` — pagamento parcial antes do vencimento;
4. `CLOSED` — fechada, ainda não vencida, sem pagamento;
5. `OPEN` — ciclo ativo acumulando compras;
6. `UPCOMING` — ciclos futuros.

## Pagamentos e estorno

`POST /credit-cards/{id}/invoices/{invId}/payments` — total ou parcial, de uma
conta própria não arquivada; não pode exceder o valor em aberto
(`PAYMENT_EXCEEDS_OUTSTANDING`, checado sob lock da fatura). O pagamento reduz o
saldo derivado da conta exatamente uma vez:

```
saldo da conta = saldo inicial + receitas − despesas − pagamentos de fatura
```

Pagamento errado se **estorna** (`…/payments/{payId}/reverse`) — o registro
permanece como `REVERSED`, saldo da conta e valor em aberto voltam, o limite é
consumido de novo. Estornar duas vezes é rejeitado. Histórico de pagamento nunca
é apagado.

## Cancelamento e edição de compra

- Metadados (descrição, estabelecimento, categoria, notas) sempre editáveis.
- Valores (total, data, nº de parcelas) só mudam enquanto **nenhuma fatura
  afetada fechou ou recebeu pagamento** — o cronograma é regenerado atomicamente;
  caso contrário: cancele e recrie, ou use um ajuste.
- Cancelamento preserva a compra e as parcelas como `CANCELLED` (fora de faturas,
  orçamentos e limite), e é bloqueado quando já houve pagamento
  (`PURCHASE_INVOICE_ALREADY_PAID`) — dinheiro que já circulou exige ajuste.

## Ajustes de fatura

`FEE`, `INTEREST`, `OTHER_DEBIT` (aumentam a fatura; todo débito exige categoria
de despesa e entra no orçamento do mês) e `CREDIT`/`REFUND` (reduzem).
Reversíveis pelo mesmo padrão auditável dos pagamentos. Crédito não pode exceder
o valor em aberto, e estornar um débito já coberto por pagamento exige estornar
o pagamento antes.

## Integração com o restante do app

- **Orçamentos**: consumo mensal = despesas regulares + parcelas ativas do mês +
  ajustes de débito com categoria − créditos elegíveis. Pagamentos nunca contam.
- **Dashboard**: despesa mensal unificada (transações + parcelas do mês);
  dívida total de cartão, limite disponível e próxima fatura são métricas
  separadas do saldo em caixa — nunca subtraídas do saldo das contas.
- **Insights**: fatura vencendo/vencida, utilização alta, pouco limite livre,
  carga de parcelas futuras.
- **Análise de compra**: o contexto financeiro considera faturas em aberto e
  parcelas futuras; opção parcelada pode referenciar um cartão e a análise
  responde limite, primeira fatura e pressão mensal projetada.
- **Lista de desejos**: `POST /wishlist/{id}/purchase` executa a opção escolhida
  como transação real (à vista) ou compra de cartão (parcelada), exatamente uma
  vez — lock no item + índice único parcial impedem duplicação por retry.

## Crédito legado

Transações antigas com `paymentMethod = CREDIT` são preservadas intactas e
marcadas `legacyCredit` (V7): continuam nos totais históricos, ganham o selo
"Crédito legado" na UI e **não** são ligadas a faturas — cartão, fechamento e
parcelamento originais são desconhecidos e não são inventados. Criar **nova**
transação comum com `CREDIT` é rejeitado (`USE_CREDIT_CARD_PURCHASE`: "Para
registrar uma nova compra no crédito, use a área de Cartões."); o formulário de
transações direciona para o fluxo de cartões.

## Limitações conhecidas

- Sem conversão automática de crédito legado em faturas (dados originais do
  cartão são desconhecidos; conversão manual deliberada fica para o futuro).
- Recorrentes não geram compras de cartão automaticamente (aguarda a
  materialização idempotente do roadmap).
- Sem crédito rotativo, refinanciamento ou saldo credor de fatura
  (pagamento acima do em aberto é rejeitado em vez de gerar crédito).
- Sem integração com emissor/banco — tudo é registrado manualmente.
