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
>
> Importação de extratos foi **concluída** — upload de CSV/OFX com
> pré-visualização determinística, parser OFX seguro sem XML/XXE, mapeamento
> de colunas assistido para formatos brasileiros, deduplicação em três níveis
> (arquivo, identidade forte, conteúdo), categorização por regras
> determinísticas, confirmação idempotente por item e desfazer auditável. Ver
> [statement-import.md](statement-import.md).
>
> Entrega de notificações foi **concluída** — caixa persistente owner-scoped,
> revisões de ciclo de vida, preferências, sincronização automática/manual e
> alertas foreground do navegador com privacidade segura por padrão. Ver
> [notifications.md](notifications.md).
>
> Histórico de preços dos itens de desejo foi **concluído** — snapshots manuais
> owner-scoped, captura de opção, idempotência, correção, resumo, alvo, gráfico e
> histórico paginado, sem coleta externa. Ver
> [wishlist-price-history.md](wishlist-price-history.md).
>
> PWA, instalabilidade e modo offline seguro foram **concluídos** — shell
> instalável, Service Worker sem cache de API e cofre IndexedDB criptografado e
> owner-isolated. Ver [pwa-offline.md](pwa-offline.md).

## Etapa em andamento

**Fila de mutações offline, idempotência e resolução de conflitos.**

O comportamento está implementado e verificado: CRUD offline para transações
comuns, orçamentos, metas, itens da lista de desejos, opções de compra e
observações manuais de preço, com fila criptografada, compactação, ordenação por
dependência, recibos duráveis owner-scoped, versões otimistas, conflitos tipados
resolvidos pelo usuário e central de sincronização. Fluxos com auditoria
própria — extratos, cartões, faturas, recorrentes, aportes, execução de compra e
captura de preço — continuam exigindo conexão **por decisão**, não por omissão.
Ver [offline-sync.md](offline-sync.md).

Verificação verde hoje: backend `test` e `verify` (377 testes), lint, typecheck,
testes unitários, build e verificação de PWA no frontend, `scripts/verify.ps1`,
a suíte focada `offline-sync.spec.ts` (29 de 29) e a suíte E2E completa
(117 aprovados, 3 pulados, nenhuma falha).

**Falta para concluir: o QA visual.** A suíte de captura roda e produz dez dos
doze estados autorais, apenas no tema claro; conflito e comparação aberta ainda
não foram capturados, e o tema escuro não chegou a rodar. A inspeção das
capturas encontrou defeitos na própria suíte — quadros tirados durante a
transição de layout, sobre esqueletos de carregamento — corrigidos mas ainda não
reverificados de ponta a ponta. Enquanto isso não fechar, a etapa não é dada por
concluída.

Pendência conhecida do produto, registrada e não corrigida nesta etapa: sair da
conta com o cofre **bloqueado** apaga a cópia local sem aviso, porque a contagem
de pendências é ilegível enquanto o cofre está fechado. Como a chave só vive em
memória, qualquer recarregamento leva a esse estado. Fechar isso exige um
marcador em texto claro no registro criptografado — muda o formato do cofre e
sua fronteira de metadados, então é decisão de uma etapa própria, não um remendo
no fim desta.

## Próxima grande etapa

Fechar o QA visual da fila offline. **Multi-moeda** só entra depois disso.

## Depois disso

- Relatórios anuais e exportação.

## Fora de cobertura deliberadamente

- Scraping de e-commerce ou automação de navegador para coletar preços.
- "IA financeira" — as recomendações continuarão determinísticas e explicáveis.
- Microserviços, filas ou infraestrutura distribuída sem necessidade medida.
