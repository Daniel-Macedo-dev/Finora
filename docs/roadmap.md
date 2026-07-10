# Roadmap

O que **não** está na primeira release, em ordem aproximada de valor. Nada aqui é
promessa de implementação — é direção.

## Próximos passos prováveis

- **Autenticação e multiusuário** — identidade, isolamento por usuário e sessões.
  A base atual (DTOs, services, sem estado global) foi desenhada para receber
  isso sem reescrita.
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
