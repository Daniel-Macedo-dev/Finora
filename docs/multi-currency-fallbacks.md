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

Recontada na árvore atual contando **ocorrências**, não linhas:

```bash
cd apps/web && rg --no-heading -o -H formatBRL src
```

`rg -c` conta linhas que casam, e várias linhas chamam o helper duas vezes — foi
assim que uma revisão anterior desta tabela subnotificou o total. O padrão
também vai sem `\b`, senão as próprias guardas não aparecem: nelas o texto é
`\bformatBRL\b`, e o `b` da barra invertida é caractere de palavra.

| Arquivo | Ocorrências |
| --- | --- |
| `features/wishlist/price-history/PriceHistorySection.tsx` | 8 |
| `features/wishlist/WishlistItemPage.tsx` | 8 |
| `features/wishlist/price-history/CaptureOptionPriceDialog.tsx` | 5 |
| `features/wishlist/ExecutePurchaseDialog.tsx` | 5 |
| `features/legacy-conversions/LegacyConversionWizard.tsx` | 4 |
| `features/goals/GoalsPage.tsx` | 4 |
| `features/credit-cards/InvoicePaymentForm.tsx` | 4 |
| `features/credit-cards/InstallmentPreview.tsx` | 4 |
| `features/wishlist/price-history/PriceHistoryChart.tsx` | 3 |
| `features/credit-cards/CardUtilization.tsx` | 3 |
| `features/wishlist/price-history/LocalPriceHistory.tsx` | 2 |
| `features/offline-sync/ConflictComparison.tsx` | 2 |
| `features/legacy-conversions/LegacyConversionsPage.tsx` | 2 |
| `features/legacy-conversions/LegacyConversionDetail.tsx` | 2 |
| `features/credit-cards/CreditPurchaseForm.tsx` | 2 |
| `features/credit-cards/CardSelect.tsx` | 2 |
| `lib/format.ts` | 1 (a própria definição) |
| `lib/format.test.ts` | 8 (testes da definição) |
| `features/statement-imports/statement-imports-currency.test.tsx` | 1 (guarda) |
| `features/notifications/notifications-currency.test.tsx` | 1 (guarda) |

Total: **71 ocorrências em 20 arquivos**, sendo 9 na definição e nos seus testes e
2 nas guardas descritas abaixo. Em produção restam **60 ocorrências em 16
arquivos**.

As notificações saíram da lista: `NotificationItem.tsx` e
`browserNotifications.ts` passaram a receber a moeda autoritativa da própria
notificação. Os números por arquivo acima divergem de revisões anteriores desta
tabela porque agora contam ocorrências; a contagem por linhas escondia chamadas
repetidas na mesma linha.

Migrados desde o levantamento inicial: `features/forecast/ForecastPage.tsx` (8)
e `features/forecast/BalanceChart.tsx` (5), junto com a previsão por moeda;
`features/wishlist/AnalysisPanel.tsx` (8), junto com a disponibilidade da análise
de compra; e toda a importação de extratos (`ConfirmImportSection` 3,
`CsvMappingStep` 2, `ImportItemEditor` 2, `ImportPreview` 2 — 9 no total), junto
com a moeda da importação. Todos passaram a receber a moeda explicitamente e
nenhum usa mais o fallback.

As duas ocorrências restantes em features já migradas são **guardas**:
`statement-imports-currency.test.tsx` e `notifications-currency.test.tsx` varrem
os arquivos de produção da própria feature com `import.meta.glob` e falham se
`formatBRL` reaparecer em qualquer um deles. Uma feature que acabou de sair do
fallback é exatamente onde ele volta por descuido, e um teste é mais barato que
uma revisão manual. A guarda das notificações também varre `.ts` além de `.tsx`
(o caminho do navegador não é um componente) e afirma quais arquivos leu, para
que um glob que deixasse de casar não passasse para sempre sem olhar nada.

## Ordem sugerida para o fechamento

1. ~~Previsão (`ForecastPage`, `BalanceChart`)~~ — **concluído** junto com a
   previsão por moeda.
2. ~~Notificações (`NotificationItem`, `browserNotifications`)~~ — **concluído**
   junto com a moeda autoritativa na resposta de notificação e a privacidade das
   reivindicações de navegador. A notificação passou a carregar `currency`, o
   risco de caixa virou um alerta por moeda, e o par valor + moeda é indivisível
   na entrega ao navegador.
3. ~~Análise de compra (`AnalysisPanel`)~~ — **concluído** junto com o estado
   tipado `EXCHANGE_RATE_REQUIRED`. O restante da lista de desejos
   (`WishlistItemPage`, `ExecutePurchaseDialog`, histórico de preços) continua
   pendente, e o QA visual da análise confirmou o efeito visível: na página de um
   item em USD o painel de análise nomeia as duas moedas corretamente enquanto
   preço de referência, preço alvo e os custos das opções logo acima ainda saem
   com `R$`. É o fallback documentado, não uma regressão — e é o argumento mais
   concreto para fechar esta lista.
4. ~~Importação de extratos~~ — **concluído** junto com a moeda da importação e
   o `CURDEF` do OFX. A pré-visualização, os totais do lote, o comparativo de
   duplicidade, a pré-visualização do mapeamento e o editor de linha recebem a
   moeda da conta de destino, e o QA visual confirmou o efeito: um extrato em
   USD nunca renderiza `R$` e um lote em JPY não inventa centavos.
5. Cartões, metas, conversões legadas e comparação de conflito — moeda já
   disponível no recurso, migração puramente de apresentação.
6. Remoção de `formatBRL` e do parâmetro opcional de `<Money>`.
