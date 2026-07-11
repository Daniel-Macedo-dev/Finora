# Roadmap

O que **não** está na primeira release, em ordem aproximada de valor. Nada aqui é
promessa de implementação — é direção.

> Autenticação e multiusuário foram **concluídos** — identidade, sessões
> server-side, posse por usuário e isolamento completo. Ver
> [security.md](security.md).

## Próximos passos prováveis

- **Cartão de crédito e ciclo de fatura** — modelo próprio de fatura/parcelamento em
  vez de transações avulsas com forma de pagamento `CREDIT`.
- **Materialização de recorrentes** — ação idempotente que converte vencimentos
  em transações reais (com prevenção de duplicatas e testes de virada de mês).
- **Fatura de cartão de crédito** — modelo próprio de fatura/parcelamento em vez
  de transações avulsas com forma de pagamento `CREDIT`.
- **Importação de extratos** — CSV com pré-visualização e OFX; mapeamento de
  categorias assistido por regras.

## Depois

- Notificações (vencimentos próximos, orçamento estourado).
- Histórico de preços dos itens de desejo (registros manuais ao longo do tempo;
  integrações reais de preço só com fonte confiável).
- PWA / offline com sincronização.
- Multi-moeda.
- Relatórios anuais e exportação.

## Fora de cobertura deliberadamente

- Scraping de e-commerce ou automação de navegador para coletar preços.
- "IA financeira" — as recomendações continuarão determinísticas e explicáveis.
- Microserviços, filas ou infraestrutura distribuída sem necessidade medida.
