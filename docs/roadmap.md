# Roadmap

O que **não** está na primeira release, em ordem aproximada de valor. Nada aqui é
promessa de implementação — é direção.

> Autenticação e multiusuário foram **concluídos** — identidade, sessões
> server-side, posse por usuário e isolamento completo. Ver
> [security.md](security.md).
>
> Cartões de crédito e ciclo de fatura foram **concluídos** — cartões com limite,
> faturas determinísticas, parcelamento exato em centavos, pagamentos com estorno
> e integração com orçamentos/dashboard/análise. Ver
> [credit-cards.md](credit-cards.md).
>
> Automação de recorrentes e previsão de caixa foram **concluídas** — definições
> semanais/mensais/anuais com destino em conta ou cartão, ocorrências com
> identidade estável e ciclo de vida completo (executar, retry, pular,
> reagendar, estornar, pausar, encerrar), materialização idempotente com
> catch-up, previsão determinística com caixa de fatura no vencimento e eventos
> prontos para notificação. Ver [recurring-automation.md](recurring-automation.md)
> e [forecast.md](forecast.md).
>
> Conversão assistida de crédito legado foi **concluída** — inventário com
> resumo e filtros, assistente com pré-visualização determinística (parcelas
> exatas, faturas, limite e redistribuição mensal), motor atômico idempotente
> sem contagem dupla, estorno auditável com guarda de liquidação, lote
> independente por item e migração de recorrentes legados sem retroativos. Ver
> [legacy-credit-conversion.md](legacy-credit-conversion.md).

## Próxima grande etapa

**Importação de extratos** — CSV com pré-visualização, importação OFX e
mapeamento de categorias assistido.

## Depois disso

- Entrega de notificações (o feed de eventos `GET /api/events/due` já fornece
  os dados: vencimentos próximos, fatura vencendo, falha de execução, caixa
  projetado insuficiente).
- Histórico de preços dos itens de desejo (registros manuais ao longo do tempo;
  integrações reais de preço só com fonte confiável).
- PWA / offline com sincronização.
- Multi-moeda.
- Relatórios anuais e exportação.

## Fora de cobertura deliberadamente

- Scraping de e-commerce ou automação de navegador para coletar preços.
- "IA financeira" — as recomendações continuarão determinísticas e explicáveis.
- Microserviços, filas ou infraestrutura distribuída sem necessidade medida.
