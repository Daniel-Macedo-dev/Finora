# Histórico de preços da lista de desejos

## Escopo e limite manual

O histórico registra observações informadas explicitamente pelo usuário. O Finora não consulta
lojas, não abre links, não faz scraping, não agenda coletas e não cria notificações de preço.
`referencePrice` continua sendo metadado livre e não é convertido em observação.

## Modelo e identidade

`wishlist_price_snapshots` guarda proprietário, item, vínculo opcional com a opção atual, chave de
série, UUID idempotente, loja normalizada, pagamento, componentes e total nominal, parcelamento,
data, URL e notas. Valores são BRL `NUMERIC(14,2)` e seguem `MoneyRules`.

Uma observação é uma cópia histórica: editar a opção atual não a reescreve. A análise de compra
continua lendo exclusivamente `purchase_options`; histórico, mínimo e alvo são informativos.

- opção associada: `OPTION:{purchaseOptionId}`;
- observação avulsa: `MANUAL:{merchantNormalizado}:{paymentKind}`.

A chave é produzida pelo servidor e permanece após a opção ser removida. O cliente não envia
`userId`, `seriesKey` nem `nominalCost`.

## Fluxos, idempotência e exclusão

- **Capturar opção atual:** o servidor copia os valores autoritativos; o navegador envia UUID,
  data, URL e notas.
- **Registrar manualmente:** sem associação ou com `updateLinkedOption=false`, só o histórico muda.
  Com `true`, snapshot e opção são gravados na mesma transação, pelas regras existentes; o cartão
  vinculado é preservado.
- **Corrigir/excluir:** afeta somente a observação e atualiza resumos imediatamente.

Cada criação exige `clientRequestId`. A exclusividade é `(user_id,client_request_id)` e um advisory
lock transacional por proprietário serializa retries. Payload idêntico devolve a linha; reutilização
conflitante retorna `PRICE_SNAPSHOT_IDEMPOTENCY_CONFLICT`. O frontend conserva o UUID durante
retries sem `localStorage`.

Excluir opção limpa o vínculo vivo e preserva loja, pagamento, valores e série. Excluir item remove
suas observações por `ON DELETE CASCADE`.

## Resumo e fórmulas

O benchmark observado é o menor valor entre a observação mais recente de cada série, com data e ID
como desempate; não é o mínimo histórico. A comparação usa a observação imediatamente anterior da
série vencedora.

- `absoluteChange = latest - previous`;
- `percentageChange = absoluteChange / previous × 100` (negativo = queda; zero anterior = `null`);
- mínimo, máximo e média são agregados no PostgreSQL, com média em duas casas;
- `distanceToTarget = benchmark - targetPrice`;
- `distanceToTargetPercentage = distanceToTarget / targetPrice × 100`;
- alvo atingido quando `benchmark <= targetPrice`.

Sem observação, o resumo pode usar a melhor opção atual para o alvo, claramente rotulada. Sem
benchmark ou alvo, distâncias são `null`.

## Histórico, gráfico e API

O gráfico retorna o menor custo por data e é limitado a 730 dias/731 pontos. A tabela usa envelope
estável, páginas de 1–100, filtros e ordenações `NEWEST`, `OLDEST`, `LOWEST`, `HIGHEST`, com ID como
desempate.

```text
POST   /api/wishlist/{itemId}/price-snapshots
POST   /api/wishlist/{itemId}/options/{optionId}/price-snapshots
GET    /api/wishlist/{itemId}/price-snapshots
GET    /api/wishlist/{itemId}/price-history-summary
GET    /api/wishlist/{itemId}/price-history-series
PUT    /api/wishlist/{itemId}/price-snapshots/{snapshotId}
DELETE /api/wishlist/{itemId}/price-snapshots/{snapshotId}
```

Sessão é obrigatória e mutações exigem CSRF. Item, opção e snapshot são resolvidos com proprietário;
agregados filtram dono e item no banco. URLs aceitam apenas `http`/`https`, host válido, até 2.000
caracteres e nenhum controle. O servidor não as busca; a UI usa texto e `noopener noreferrer`.

## Concorrência e limitações

Testes com threads e PostgreSQL real cobrem retry, conflito, modo somente histórico, atualização
vinculada, UUID igual entre donos e agregados consistentes. Não há monitoramento automático,
previsão, extensão, API de loja, alertas ou notificações de preço. Dados históricos são referências
manuais, não ofertas atuais verificadas.

### Defeito conhecido: data padrão em UTC

Registrar um preço falha para quem está a oeste de Greenwich no fim da noite. O
diálogo inicializa a data com `new Date().toISOString().slice(0, 10)`
(`CaptureOptionPriceDialog.tsx`), que é a data **UTC**, enquanto o servidor
recusa qualquer observação depois de `LocalDate.now(clock)`
(`PriceHistoryService.validateDate`). Em UTC−3, das 21h à meia-noite o
formulário já propõe amanhã: a gravação volta com `PRICE_SNAPSHOT_FUTURE_DATE` e
o diálogo permanece aberto.

Comprovado: `e2e/price-history.spec.ts` reprova dois casos nessa faixa horária e
passa 11/11 com a mesma árvore quando a API roda com `-Duser.timezone=UTC`, o
que alinha o relógio do servidor à data que o cliente envia. A correção é usar a
data local no cliente; ainda não foi feita, e é trabalho da lista de desejos, não
da fatia de moeda.
