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

## Próxima grande etapa

**Automação de recorrentes e motor de previsão** (Finora Recurring Automation &
Forecast Engine):

- materialização idempotente de recorrentes em transações reais (com prevenção
  de duplicatas e testes de virada de mês);
- compras de cartão recorrentes;
- projeção de transações agendadas;
- previsão de fluxo de caixa futuro;
- eventos de vencimento prontos para notificação.

## Depois disso

- **Conversão assistida de crédito legado** — transformar transações `CREDIT`
  antigas em compras de cartão reais quando o usuário informar os dados do cartão.
- **Importação de extratos** — CSV com pré-visualização e OFX; mapeamento de
  categorias assistido por regras.

- Notificações (vencimentos próximos, fatura vencendo, orçamento estourado).
- Histórico de preços dos itens de desejo (registros manuais ao longo do tempo;
  integrações reais de preço só com fonte confiável).
- PWA / offline com sincronização.
- Multi-moeda.
- Relatórios anuais e exportação.

## Fora de cobertura deliberadamente

- Scraping de e-commerce ou automação de navegador para coletar preços.
- "IA financeira" — as recomendações continuarão determinísticas e explicáveis.
- Microserviços, filas ou infraestrutura distribuída sem necessidade medida.
