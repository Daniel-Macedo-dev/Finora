# Segurança e identidade

Finora é multiusuário: cada dado financeiro pertence a um usuário autenticado e
nenhum usuário consegue ler, inferir, alterar, excluir ou influenciar os dados de
outro — nem por CRUD direto, nem por agregações (dashboard, orçamentos, contexto
financeiro, análise de compra, insights).

## Autenticação

- **Sessão server-side** (Spring Security + Spring Session JDBC), identificada por
  um cookie **HttpOnly** `FINORA_SESSION`. Nenhum token de autenticação chega ao
  JavaScript nem ao `localStorage`.
- Senhas são armazenadas com **BCrypt** (`PasswordEncoder`), custo configurável
  (`finora.security.bcrypt-strength`, padrão 10; testes usam 4 por velocidade).
  Nunca em texto puro, nunca logadas, nunca serializadas em DTOs.
- Entrada de identidade é normalizada: e-mail é aparado e comparado em minúsculas;
  unicidade garantida na aplicação e no banco (`unique index lower(email)`).

## Sessão

- Persistida no PostgreSQL (`SPRING_SESSION`), então sobrevive a reinícios do
  backend enquanto o banco existir. O esquema é gerido por Flyway (V5), com
  `spring.session.jdbc.initialize-schema=never` — sem inicialização automática
  paralela.
- Timeout configurável (`FINORA_SESSION_TIMEOUT`, padrão 12h). Cookie: HttpOnly,
  `SameSite=Lax`, `Secure` configurável (`FINORA_COOKIE_SECURE`, ligar em HTTPS),
  `Path=/`.
- Login programático rotaciona o id da sessão (proteção contra fixation).

## CSRF

- Proteção CSRF **habilitada** para todos os métodos que alteram estado. Integração
  SPA por double-submit: token num cookie legível `XSRF-TOKEN`, reenviado no header
  `X-XSRF-TOKEN`. Bootstrap em `GET /api/auth/csrf`.
- O cliente web centraliza isso em `lib/api.ts`: obtém o token antes da primeira
  mutação, anexa o header em POST/PUT/PATCH/DELETE, e **nunca** repete mutações
  financeiras automaticamente após falha (evita escrita duplicada).

## Superfície pública vs protegida

Público (mínimo): `POST /api/auth/register`, `POST /api/auth/login`,
`POST /api/auth/claim-legacy`, `GET /api/auth/csrf`, `/error`.

Todo o restante de `/api/**` exige autenticação e responde **401 Problem Details**
(`AUTH_UNAUTHENTICATED`) em vez de redirecionar. O backend é a fronteira de
segurança — os guardas de rota do frontend são apenas UX.

## Resistência à enumeração de usuários

Login inválido — senha errada **ou** e-mail inexistente — retorna sempre o mesmo
corpo genérico 401 (`AUTH_INVALID_CREDENTIALS`). Contas desabilitadas ou legadas
pendentes também não autenticam e produzem o mesmo corpo. Recursos de outro dono
resolvem para **404 Not Found** (nunca 403), evitando confirmar sua existência.

## Modelo de posse

- Cada entidade financeira tem `user_id` obrigatório (`accounts`, `categories`,
  `transactions`, `budgets`, `commitments`, `goals`, `wishlist_items`,
  `app_settings`, `statement_import_batches`, `statement_import_items`,
  `category_mapping_rules`). `purchase_options` herda a posse pelo item pai.
- A posse vem **sempre** da identidade autenticada (`CurrentUserProvider`),
  nunca de parâmetros ou corpo da requisição — `?userId=` é ignorado.
- Repositórios usam acesso owner-scoped (`findByIdAndUserId`, `findAllByUserId…`).
  A busca de transações tem o predicado de dono como raiz obrigatória da
  Specification, então nenhuma combinação de filtros roda sem escopo — inclusive a
  query de contagem da paginação.
- O banco reforça com chaves compostas: `UNIQUE (id, user_id)` em categorias,
  contas e itens de desejo, e FKs compostas
  (`transactions.(category_id, user_id) → categories(id, user_id)` etc.), de modo
  que uma referência cross-owner é rejeitada pelo próprio banco mesmo se a
  aplicação falhasse.
- Uniqueness passou a ser por usuário: nome de conta por usuário, (nome, tipo) de
  categoria por usuário, (mês, categoria) de orçamento por usuário, uma linha de
  settings por usuário.
- A superfície de conversão de crédito legado segue o mesmo modelo: inventário,
  elegibilidade, preview, conversão, detalhe, estorno, lote e mapeamento de
  recorrente são owner-scoped ponta a ponta (ids alheios respondem 404), e o
  ledger `legacy_credit_conversions` amarra origem, compra e cartão ao dono por
  FKs compostas — provado em `LegacyConversionOwnershipTest`.
- A importação de extratos também segue o mesmo modelo: lote, item e regra de
  categoria respondem 404 para outro dono; o arquivo bruto de um upload CSV
  fica em armazenamento temporário fora do banco, referenciado por um token
  aleatório validado por regex antes de qualquer leitura em disco; o parser
  OFX não usa nenhum parser XML (sem XXE possível) e rejeita `<!DOCTYPE`/
  `<!ENTITY` mesmo assim, por defesa em profundidade — provado em
  `StatementImportOwnershipTest`. Ver [statement-import.md](statement-import.md).

## Migração de dados v1 (claim legado)

A migração V4 preserva dados anteriores ao multiusuário: se o banco já continha
dados pessoais, todos são atribuídos a um usuário
`PENDING_LEGACY_CLAIM` (`legacy@finora.local`, hash impossível de casar) que **não
autentica**. Um registro comum não herda esses dados — "primeiro a registrar leva
tudo" **não** é o comportamento.

A transferência acontece só pelo fluxo `POST /api/auth/claim-legacy`, habilitado
por um segredo de ambiente `FINORA_LEGACY_CLAIM_TOKEN` (nunca commitado):
comparação em tempo constante, uma única vez (após o sucesso o usuário pendente
some, então o endpoint passa a reportar indisponibilidade permanentemente). Sem o
segredo configurado o fluxo fica desabilitado. Como os dados já apontam para o id
do usuário pendente, o claim é uma única atualização transacional de identidade —
sem reescrita linha a linha e sem estados parciais.

## CORS

Origens permitidas são explícitas e configuráveis
(`FINORA_CORS_ALLOWED_ORIGINS`). Como a autenticação usa cookie, credenciais são
permitidas — portanto **nunca** wildcard de origem. Exposto como
`CorsConfigurationSource` para integrar à cadeia de filtros do Spring Security.
Implantação same-origin é a direção preferida.

## Limitações conhecidas

- Sem verificação de e-mail.
- Sem fluxo de recuperação/redefinição de senha por e-mail.
- Sem MFA/TOTP/passkeys.
- Sem OAuth/login social.
- Sem rate limiting distribuído de login (adequado a um app pessoal same-origin;
  seria o próximo passo de endurecimento numa implantação exposta).

## Notificações

Preferências, listagens, contagens, ações, sync e claims derivam o proprietário
da sessão; não aceitam `userId`, e ids alheios respondem 404. Toda mutação exige
CSRF. Claims concorrentes são deduplicados no PostgreSQL. Valores ficam fora do
alerta nativo por padrão, nenhum conteúdo é salvo no `localStorage` e rotas de
clique são internas. A permissão do navegador depende de gesto explícito. Não há
SMTP, Web Push, Service Worker ou segredo de provedor. Ver
[notifications.md](notifications.md).
