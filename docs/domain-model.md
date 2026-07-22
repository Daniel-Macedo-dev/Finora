# Modelo de domínio

## Entidades e relacionamentos

Tudo pertence a um `User`. Cada entidade financeira carrega `user_id` obrigatório
(exceto `PurchaseOption`, que herda a posse pelo item pai).

```
User 1──n Account
User 1──n Category ──┐
User 1──n Transaction n──1 Category, 0..1 Account
User 1──n Budget      n──1 Category   (unique: user + mês + categoria)
User 1──n Commitment  n──1 Category, 0..1 Account, 0..1 CreditCard
Commitment 1──n CommitmentOccurrence ──0..1 {Transaction, CardPurchase}
User 1──n Goal
User 1──n WishlistItem 1──n PurchaseOption, 0..1 Category
WishlistItem 1──n WishlistPriceSnapshot 0..1──1 PurchaseOption
User 1──1 AppSettings
User 1──n CreditCard 1──n CardInvoice 1──n {CardInstallment, InvoicePayment, InvoiceAdjustment}
CreditCard 1──n CardPurchase 1──n CardInstallment
User 1──n StatementImportBatch n──1 Account
StatementImportBatch 1──n StatementImportItem ──0..1 Transaction (statement_import_item_id)
User 1──n CategoryMappingRule n──1 Category, 0..1 Account
```

`WishlistPriceSnapshot` é uma observação corrigível que copia valores históricos
e possui série controlada pelo servidor. Excluir opção limpa só o vínculo;
excluir item remove o histórico. Ver
[wishlist-price-history.md](wishlist-price-history.md).

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
domínio de cartões ([credit-cards.md](credit-cards.md)). Um registro legado
pode ser **convertido** em compra real: a conversão ativa o desativa
financeiramente (`financiallyActive = false`) e ele permanece apenas como
auditoria, protegido contra edição e exclusão
([legacy-credit-conversion.md](legacy-credit-conversion.md)).

### Importação de extrato (`statement_import_batches`, `statement_import_items`, `category_mapping_rules`)
Upload de CSV/OFX de conta corrente/poupança tem documento dedicado:
[statement-import.md](statement-import.md). Invariante central: linhas
parseadas são só pré-visualização até a confirmação; um item incluído gera no
máximo uma transação real (`statement_import_item_id` único e parcial), e
desfazer remove o efeito financeiro sem apagar o ledger de importação.

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

### Definição recorrente (`commitments`)
`WEEKLY` (a cada 7 dias a partir de `start_date`), `MONTHLY` (com `due_day`
obrigatório, ajustado ao tamanho do mês — dia 31 vira 28/29 em fevereiro e
volta a 31 em março) ou `YEARLY` (aniversário da data de início; 29/02 ajusta
em anos não bissextos). Período `[start_date, end_date?]` inclusivo; inativo
sai de todas as projeções e do processamento. `RecurrenceCalculator` é a única
fonte de verdade das datas — usado por preview, processamento, forecast,
dashboard e análise de compra.

Cada definição tem modo de execução (`MANUAL`/`AUTOMATIC`) e destino
(`PROJECTION_ONLY`, `ACCOUNT_TRANSACTION` com conta própria não arquivada, ou
`CREDIT_CARD_PURCHASE` com cartão próprio ativo e 1–120 parcelas) — coerência
garantida por constraints. Legados com `payment_method = 'CREDIT'` permanecem
somente-planejamento até ganharem um cartão real. Ver
[recurring-automation.md](recurring-automation.md).

### Ocorrência (`commitment_occurrences`)
Identidade estável `UNIQUE (commitment_id, scheduled_date)`; reagendamento
altera só `effective_date`. Ciclo de vida
`SCHEDULED → MATERIALIZED | SKIPPED | FAILED`, `MATERIALIZED → REVERSED`
(terminal). No máximo um artefato gerado por ocorrência (transação **ou**
compra de cartão), com índices únicos parciais garantindo que cada artefato
pertence a exatamente uma ocorrência. Histórico materializado é imutável a
edições da definição.

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

### Preferências e notificações (`notification_preferences`, `notifications`)

Cada usuário possui exatamente uma preferência. Cada fonte possui no máximo uma
notificação por usuário; `revision` separa ciclos de leitura, dispensa, snooze e
entrega no navegador. `resolved_at` encerra a apresentação sem apagar histórico,
e o reaparecimento reativa a mesma linha. `resource_type/resource_id` são apenas
metadados de navegação, nunca FKs polimórficas nem autoridade financeira. Ver
[notifications.md](notifications.md).
