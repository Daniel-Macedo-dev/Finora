# Modelo de domínio

## Entidades e relacionamentos

Tudo pertence a um `User`. Cada entidade financeira carrega `user_id` obrigatório
(exceto `PurchaseOption`, que herda a posse pelo item pai).

```
User 1──n Account
User 1──n Category ──┐
User 1──n Transaction n──1 Category, 0..1 Account
User 1──n Budget      n──1 Category   (unique: user + mês + categoria)
User 1──n Commitment  n──1 Category
User 1──n Goal
User 1──n WishlistItem 1──n PurchaseOption, 0..1 Category
User 1──1 AppSettings
User 1──n CreditCard 1──n CardInvoice 1──n {CardInstallment, InvoicePayment, InvoiceAdjustment}
CreditCard 1──n CardPurchase 1──n CardInstallment
```

Uniqueness é por usuário e, onde a aplicação compara sem distinção de caixa, o
banco usa índices case-insensitive: nome de conta por usuário; (nome, tipo) de
categoria por usuário; (mês, categoria) de orçamento por usuário; uma linha de
settings por usuário; e-mail global case-insensitive. Chaves compostas
`UNIQUE (id, user_id)` + FKs compostas impedem referências cross-owner no próprio
banco. Ver [security.md](security.md).

## Conceitos e invariantes

### Conta (`accounts`)
Tipos: `CHECKING`, `SAVINGS`, `CASH`, `OTHER`. Guarda apenas o **saldo inicial**;
o saldo atual é derivado (`inicial + receitas − despesas` da conta). Nome único.
Conta com transações **não pode ser excluída** (`ACCOUNT_HAS_TRANSACTIONS`) —
arquive para preservar o histórico. O saldo derivado também desconta os
**pagamentos de fatura de cartão** liquidados pela conta (exatamente uma vez —
estorno devolve). Cartão de crédito não é uma conta: é um domínio próprio com
faturas e parcelas — ver [credit-cards.md](credit-cards.md).

### Categoria (`categories`)
`INCOME` ou `EXPENSE`; única por (nome, tipo). O tipo é imutável após a criação
(histórico permaneceria inconsistente). Categorias com transações não podem ser
excluídas — desative (`active=false`); inativas continuam válidas no histórico e
somem apenas dos formulários de criação. Um conjunto padrão em pt-BR é criado
pela migração V1 (`is_default=true`).

### Transação (`transactions`)
`type` (`INCOME`/`EXPENSE`) + **valor sempre positivo** (check no banco). O sinal
é semântico, nunca aritmético no armazenamento. A categoria deve ter o mesmo tipo
da transação (`CATEGORY_TYPE_MISMATCH`). Conta, forma de pagamento
(`PIX`, `DEBIT`, `CASH`, `BANK_TRANSFER`, `OTHER`) e observações são opcionais.
Busca paginada (máx. 100/página) com filtros combinados por AND.

**`CREDIT` é somente legado**: transações antigas com essa forma de pagamento
são preservadas e marcadas `legacyCredit` (sem ligação com faturas); criar uma
nova é rejeitado (`USE_CREDIT_CARD_PURCHASE`) — compras no crédito vivem no
domínio de cartões ([credit-cards.md](credit-cards.md)).

### Cartão de crédito (`credit_cards` e satélites)
Cartões, faturas, compras, parcelas, pagamentos e ajustes têm documento
dedicado: [credit-cards.md](credit-cards.md). Invariante central: despesa de
cartão conta **uma vez**, nas parcelas ativas do mês da fatura; o pagamento da
fatura movimenta a conta, nunca a despesa.

### Orçamento (`budgets`)
Par (mês, categoria de despesa) único — o banco também garante
(`uq_budgets_month_category`). Guarda apenas o limite; consumo, restante,
percentual e status são derivados das transações do mês na leitura. Status:
`HEALTHY` < limiar de alerta ≤ `WARNING` < 100% ≤ `EXCEEDED` (limiar em
`app_settings.budget_warning_threshold`). Mês e categoria são imutáveis.

### Compromisso recorrente (`commitments`)
`MONTHLY` (com `due_day` obrigatório, ajustado ao tamanho do mês — dia 31 vira
28/29 em fevereiro) ou `YEARLY` (aniversário da data de início). Período
`[start_date, end_date?]`; inativo sai de todas as projeções. `occurrenceIn(mês)`
é a única fonte de verdade das ocorrências — usada por projeções, dashboard e
análise de compra. Compromissos **não geram transações automaticamente** nesta
release: são dados de planejamento.

### Meta (`goals`)
`current_amount` é gerido pela aplicação (aportes via
`POST /goals/{id}/contributions`; retirada = valor negativo, nunca abaixo de
zero). `COMPLETED` quando atual ≥ alvo; arquivamento manual. Sugestão mensal =
restante ÷ meses até a data alvo (mínimo 1; null sem data futura).

### Item de desejo (`wishlist_items`) e opções (`purchase_options`)
Status: `PLANNING → MONITORING → READY_TO_BUY → PURCHASED / ARCHIVED` (transições
livres — o usuário manda). Prioridade: `LOW/MEDIUM/HIGH/ESSENTIAL`. Cada opção é
`CASH` ou `INSTALLMENT`:

- `CASH` **não pode** carregar parcelas (`OPTION_CASH_WITH_INSTALLMENTS`);
- `INSTALLMENT` exige nº e valor de parcela, e `n × parcela` deve conferir com o
  total anunciado dentro de `±0,01 × n` (`OPTION_INSTALLMENTS_DONT_RECONCILE`);
- o banco espelha a regra (`ck_options_kind_consistency`).

`nominalCost = preço + frete + taxas`. Excluir o item remove as opções (cascade).

### Usuário (`users`)
`displayName`, `email` (normalizado, único case-insensitive), `passwordHash`
(BCrypt) e `status` (`ACTIVE`, `DISABLED`, `PENDING_LEGACY_CLAIM`). O status
pendente é o dono placeholder dos dados v1 e não autentica até o claim. Categorias
padrão e settings são criadas por usuário no registro (fonte única:
`DefaultCategoryCatalog`).

### Configurações (`app_settings`, uma linha por usuário)
| Campo | Default | Papel |
| --- | --- | --- |
| `minimum_cash_buffer` | 0 | caixa que nunca deve ser violado por compra à vista |
| `max_installment_commitment_ratio` | 0,30 | teto de (parcela + recorrentes) ÷ renda média |
| `monthly_opportunity_rate` | 0 | taxa mensal para valor presente (0 = comparação nominal) |
| `budget_warning_threshold` | 0,80 | consumo que dispara o estado WARNING |

Defaults conservadores, criados no registro, visíveis na tela de configurações e
usados de forma explícita na resposta da análise (`assumptions`). A análise e os
alertas de orçamento usam sempre as settings do usuário autenticado.
