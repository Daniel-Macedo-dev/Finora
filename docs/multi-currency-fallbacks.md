# Chamadas ainda em BRL fixo

Inventário das chamadas de apresentação monetária que **ainda** assumem BRL, para
a etapa de fechamento da release multi-moeda. Nenhum componente introduzido nesta
fase usa o fallback: `CurrencyStat`, `CurrencyTotal`, `CategoryBars`,
`TrendChart`, o painel e a tela de orçamentos passam a moeda explicitamente.

O fallback vive em dois lugares:

- `formatBRL` em `src/lib/format.ts` — formata sempre em reais;
- o parâmetro opcional `currency` de `<Money>`, que assume `'BRL'` quando omitido.

Ambos permanecem apenas para não quebrar as telas ainda não migradas, e são
removidos quando a última delas passar a moeda.

## Contagem de `formatBRL` por arquivo

Levantada em `f1d7082..HEAD` com `rg formatBRL apps/web/src`.

| Arquivo | Ocorrências |
| --- | --- |
| `features/wishlist/AnalysisPanel.tsx` | 8 |
| `features/wishlist/price-history/PriceHistorySection.tsx` | 8 |
| `features/wishlist/WishlistItemPage.tsx` | 7 |
| `features/wishlist/ExecutePurchaseDialog.tsx` | 5 |
| `features/credit-cards/InvoicePaymentForm.tsx` | 4 |
| `features/legacy-conversions/LegacyConversionWizard.tsx` | 4 |
| `features/credit-cards/InstallmentPreview.tsx` | 4 |
| `features/goals/GoalsPage.tsx` | 4 |
| `features/wishlist/price-history/CaptureOptionPriceDialog.tsx` | 3 |
| `features/wishlist/price-history/PriceHistoryChart.tsx` | 3 |
| `features/statement-imports/ConfirmImportSection.tsx` | 3 |
| `features/credit-cards/CardUtilization.tsx` | 3 |
| `features/notifications/NotificationItem.tsx` | 2 |
| `features/notifications/browserNotifications.ts` | 2 |
| `features/legacy-conversions/LegacyConversionDetail.tsx` | 2 |
| `features/legacy-conversions/LegacyConversionsPage.tsx` | 2 |
| `features/wishlist/price-history/LocalPriceHistory.tsx` | 2 |
| `features/statement-imports/CsvMappingStep.tsx` | 2 |
| `features/statement-imports/ImportItemEditor.tsx` | 2 |
| `features/statement-imports/ImportPreview.tsx` | 2 |
| `features/credit-cards/CreditPurchaseForm.tsx` | 2 |
| `features/credit-cards/CardSelect.tsx` | 2 |
| `features/offline-sync/ConflictComparison.tsx` | 2 |
| `lib/format.ts` | 1 (a própria definição) |
| `lib/format.test.ts` | 8 (testes da definição) |

Total: **87 ocorrências em 25 arquivos**, sendo 9 na definição e nos seus testes.

Migrados desde o levantamento inicial: `features/forecast/ForecastPage.tsx` (8) e
`features/forecast/BalanceChart.tsx` (5), junto com a previsão por moeda — ambos
passaram a receber a moeda explicitamente e nenhum usa mais o fallback.

## Ordem sugerida para o fechamento

1. ~~Previsão (`ForecastPage`, `BalanceChart`)~~ — **concluído** junto com a
   previsão por moeda.
2. Notificações (`NotificationItem`, `browserNotifications`) — depende da moeda
   autoritativa na resposta de notificação.
3. Análise de compra e lista de desejos — depende do estado tipado
   `EXCHANGE_RATE_REQUIRED`.
4. Importação de extratos — depende da moeda da conta na pré-visualização.
5. Cartões, metas, conversões legadas e comparação de conflito — moeda já
   disponível no recurso, migração puramente de apresentação.
6. Remoção de `formatBRL` e do parâmetro opcional de `<Money>`.
