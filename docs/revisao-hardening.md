# Revisão de arquitetura — críticas e melhorias

Resultado da revisão (Opus) sobre a POC, com a melhoria aplicada para cada achado.
Status: **corrigido** (commit) / **planejado** (não começado).

## A — Bloqueadores de produção

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 1 | Sem timeout HTTP; 4 jobs compartilham 1 thread do scheduler — socket pendurado trava o processo todo, `/actuator/health` continua UP | `connectTimeout` 5s + `readTimeout` 10s no `RestClient`; `spring.task.scheduling.pool.size: 4` | corrigido `41c3291` |
| 2 | `ProcessamentoEstado.valueOf` lança em status fora do enum e aborta a reconciliação inteira | parse seguro: status desconhecido → `PROCESSANDO` (mantém ENVIADO, tenta de novo) | corrigido `41c3291` |
| 3 | HTTP dentro da transação do advisory lock → `xmin` aberto por minutos, rollback tudo-ou-nada, `repararLeases` atrás do I/O lento | lock de **sessão** (`pg_try_advisory_lock`/`unlock` em conexão dedicada); trabalho em transações curtas | corrigido `41c3291` |

## B — Perda/duplicação sob operação normal

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 4 | Lease vencido reenvia e **perde o lote_id** do primeiro envio (não é só crash — envio > lease) | gravar `lote_id` logo após o POST; `repararLeases` promove ENVIADO quando há `lote_id` | corrigido |
| 5 | Paginação por offset: inserção/remoção no legado desloca a janela e **pula** operações (checkpoint monotônico não revisita) | checkpoint por cursor (`after_id`), não por página | corrigido |
| 6 | DLQ gravada dentro da mesma transação que pode abortar → página inteira perdida | SAVEPOINT por item (nested) + DLQ em segunda transação | corrigido |

## C — Retry e estados presos

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 7 | `retryavel` é flag morta — 4xx é retentado 5× antes da DLQ; 401 despeja a fila | decidir por `retryavel` (4xx→DLQ imediata); 401 invalida token cacheado | corrigido |
| 8 | `PROCESSANDO` eterno e lote REJEITADO vazio ficam ENVIADO para sempre, martelando a CERC; `idadePendente` mede `next_attempt_at` (negativo com backoff); zero métricas | contador de consultas + limite→DLQ; idade por `criado_em`; gauges Micrometer | corrigido |
| 9 | `falhaRetryavel` descarta `erro`; `marcarDlq` sem guarda | gravar `ultimo_erro`; guarda de estado | corrigido |

## D — Taxa/concorrência

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 10 | Rate limiter in-process → N pods = N×80 rps contra a CERC; autoscaling aumenta pressão no gargalo | token bucket no Postgres (`rate_limit`), teto global | corrigido |

## E — Segurança

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 11 | Secrets com default `poc-*` que valem em produção, sem fail-fast | guard que aborta o boot fora de `demo/test/mock-*` | corrigido |
| 12 | Webhook não valida `lote_id`; `statusLote` é lido e ignorado; 200 em falha transitória troca retry por trabalho manual | validar `lote_id`, usar `statusLote`, 500 em falha transitória | corrigido |

## F — Domínio/compliance

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 13 | `assinatura="S"`, `tipo="MERC"`, `parcela=1`, `fatura=referencia.take(60)` hardcoded — afirmação jurídica sem dado | campos reais no payload canônico; rejeitar onde o legado não fornecer; escala do BigDecimal | corrigido |
| 14 | `titulo.duplicata_id UNIQUE` bloqueia multi-registradora | pré-requisito: chave `(registradora, duplicata_id)` — aguarda roteamento | pré-requisito |

## G — Operação/observabilidade

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 15 | DLQ é write-only — sem listagem nem reprocessamento | `GET /api/dlq` + `POST /api/dlq/{id}/reprocessar` + transições | corrigido |
| 16 | Menores: backoff pelo 1º item do lote · case de registradora normalizado 3× · `contarPendentes` no repo errado · worker sem `runCatching` · `DUPE_POD_ID` default compartilhado | corrigir cada um | corrigido |

## Segundo round — os consertos criaram problemas piores

O primeiro round consertou os achados originais e introduziu quatro regressões
mais graves que o que corrigia. Achadas relendo o diff, não pela suíte (que
estava verde).

| # | Regressão introduzida | Melhoria | Status |
|---|---|---|---|
| 17 | `DlqController` **sem autenticação nenhuma** — `GET /api/dlq` devolve CPF/CNPJ, e-mail, chave PIX e IBAN em claro; `POST .../reprocessar` reenfileira registro sem credencial | `X-Api-Key` própria de admin (`dupe.admin.api-key`, distinta da de ingestão) via `ChaveApi` com comparação em tempo constante; `limite` limitado a 500; chave entra no `CredenciaisGuard` | corrigido |
| 18 | `incrementarConsultas` contava **antes** da consulta e independia do resultado: a janela 423 das 20h às 8h condenava todo lote em voo a `FALHA_PERMANENTE` em ~1 hora | contador só sobe em consulta bem-sucedida que responda `PROCESSANDO`; preso passa a `INDETERMINADO` (estado novo, V5), terminal, com `lote_id` e referência no payload da DLQ, e recusado pelo reprocesso | corrigido |
| 19 | `retryavel = false` mandava o lote inteiro para a DLQ na primeira tentativa: um `base-url` errado ou um 403 do WAF drenava o backlog | 403/404/405/408/415 reclassificados como rota/acesso (transitório, não esgota); 400/422 bissectam o lote até isolar o item ruim | corrigido |
| 20 | Reprocesso da DLQ commitava update parcial e devolvia 409: operação em `PENDENTE` **sem linha de fila**, invisível para o worker e fora dos estados terminais | os dois updates e a baixa da DLQ numa transação com `setRollbackOnly` no caminho de falha | corrigido |
| 21 | **Latente, pré-existente:** `falhaPermanente` fazia `CAST('texto' AS jsonb)` na coluna `erros` — todo caminho de falha permanente lançava `DataIntegrityViolationException` em vez de gravar, então "tentativas esgotadas → DLQ" nunca funcionou | `jsonb_build_object('erro', :erro)` + `ultimo_erro` | corrigido |

Achado 21 só apareceu ao escrever o teste do bisect. Revisão por leitura não o
pegou em dois rounds.

Cobertura nova: `HardeningTest` (7 testes) — autenticação da DLQ em ambos os
verbos, orçamento de consultas preservado sob falha, `INDETERMINADO` em vez de
`FALHA_PERMANENTE`, atomicidade do reprocesso, bisect isolando o item ruim, rota
errada não drenando a fila. Suíte: 20 testes, verde.

## Terceiro round — o que tinha ficado como dívida

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 22 | Advisory lock de sessão vazava: `unlock` falho devolvia a conexão ao pool com o lock preso até o `maxLifetime` do Hikari (30 min), sem reconciliação e sem alerta; `pg_try_advisory_lock` reentrante fazia um `unlock` não soltar | `pg_advisory_unlock_all()` ao pegar a conexão, `unlock` no `finally`, e `evictConnection` + log de erro se o `unlock` falhar | corrigido |
| 23 | Teto global de taxa cobria só `enviar`; `consultar` da reconciliação ficava fora, somando 2× o limite. E era janela fixa de 1s, não token bucket: 2× o teto na virada | token bucket real em tabela (recarga contínua, burst limitado, nada consumido quando nega), aplicado a `enviar` e `consultar` | corrigido |
| 24 | `lote_id` divergente levantava exceção e revertia a transação do webhook inteira — inclusive a linha de dedup — então cada retry da registradora gerava nova linha na DLQ para o mesmo evento | divergência descarta só o item, com motivo na DLQ; dedup commita e os itens legítimos são aplicados | corrigido |
| 25 | `titulo.duplicata_id UNIQUE` com `ON CONFLICT DO NOTHING` sem target: dupla escrituração era engolida em silêncio, perdendo o sinal mais importante do domínio | `UNIQUE (registradora, duplicata_id)` para guardar o histórico + índice único parcial `(duplicata_id) WHERE ativo` para no máximo um título ativo; inserção devolve `NOVO`/`JA_EXISTE`/`CONFLITO_ATIVO` e o conflito vira incidente na DLQ | corrigido |
| 26 | Idade da fila usava `criado_em`, então qualquer reprocesso da DLQ fazia o alerta de idade disparar para sempre | `coalesce(reenfileirado_em, criado_em)` | corrigido |
| 27 | `CredenciaisGuard` casava só o prefixo `poc-` e ignorava a senha do banco | lista de placeholders conhecidos, piso de 16 caracteres, cobre `spring.datasource.password` e `client-id` | corrigido |
| 28 | **Latente:** `idadePendenteMaisAntigoMs` devolvia 0 em vez de `null` com fila vazia — `rs.getDouble` mapeia SQL NULL para 0.0 | consulta com `ORDER BY ... LIMIT 1`, sem linha quando a fila está vazia | corrigido |

Achado 28, como o 21, só apareceu ao escrever o teste. Dois de dois bugs latentes
vieram de testes, nenhum de leitura de código.

Cobertura nova: `HardeningSegundoRoundTest` (8 testes). Suíte: 28 testes.

## Quarto round — regressões dos próprios consertos

Código inalterado desde o round 3; a rodada saiu inteira de leitura e de
raciocínio sobre interleavings. Nenhum destes tinha teste.

| # | Crítica | Melhoria | Status |
|---|---|---|---|
| 29 | Lote em `ERRO` era reenviado cegamente, com desfecho por item desconhecido — tratamento oposto ao de `abandonarPresos`. E como o reenvio gera `lote_id` novo, o webhook do lote antigo chegava divergente e era descartado pelo guard de integridade: registradora com dois títulos, nós com um, evidência no lixo | `ERRO` de lote vai para `INDETERMINADO` como qualquer desfecho desconhecido; `resetar`/`reabrir`/`resetarParaPendente` removidos | corrigido |
| 30 | Bisect sem prazo: 200 itens todos ruins dão até 399 chamadas × 10s de `readTimeout` ≈ 66 min contra lease de 10 min. Lease vencia no meio, outro pod reenviava, e o bisect original carimbava `lote_id` em cima da operação alheia — dupla escrituração não reconciliável de volta | `Claim` carrega `claimed_until`; antes de cada chamada o bisect confere o relógio contra o prazo menos 25% do lease e devolve o resto à fila | corrigido |
| 31 | Bisect disparava em erro local (registradora fora do registry), gerando 2n−1 recursões inúteis que queimavam o teto de taxa antes de mandar tudo para a DLQ | `RegistradoraException.rejeicaoDeConteudo`, marcada só em 400/422; bisect exige essa origem | corrigido |
| 32 | Corrida no índice `idx_titulo_ativo` lança `DuplicateKeyException`, que não é `RegistradoraException` e escapava o único `catch` do laço — cancelava a passada inteira, inclusive `abandonarPresos` | `runCatching` por item na aplicação de resultados | corrigido |
| 33 | `idadePendenteMaisAntigoMs` virou `ORDER BY coalesce(...) LIMIT 1` no round 3, sem índice para a expressão: sort do conjunto `PENDENTE` inteiro a cada tick do alerta e a cada scrape do Prometheus, em todos os pods | índice de expressão parcial (V9) | corrigido |
| 34 | Negativa do token bucket fazia `return@forEach`: depois de indisponibilidade, milhares de UPDATEs serializados na mesma linha por ciclo, sem trabalho útil | `break` na primeira negativa | corrigido |
| 35 | `naoEsgota` (429/423) reagendava com backoff exponencial sobre `attempt_count` já incrementado pelo claim — espera de janela virava espiral até o cap de 600s | `atrasoEspera()`, atraso fixo curto com jitter | corrigido |
| 36 | `CredenciaisGuard` aplicava piso de 16 caracteres e lista de proibidos ao `client-id`, que não é segredo e é emitido pela registradora: app em crash loop com credencial legítima | `client-id` fora da checagem; `dupe.credenciais-impostas` como dispensa explícita | corrigido |
| 37 | Fallback do `evictConnection` só logava e o `close()` seguinte devolvia ao pool a conexão que ainda segurava o lock — o bug do round 3 voltava em silêncio se o DataSource viesse embrulhado | `pg_terminate_backend(pg_backend_pid())` quando o evict falha | corrigido |
| 38 | `RateLimiterDb` usava `now()`, timestamp da transação: qualquer transação envolvendo o worker congelaria a recarga e travaria o envio | `clock_timestamp()` | corrigido |
| 39 | `buscarLotesEnviados` e `buscarEnviadosPresos` sem `LIMIT`; `abandonarPresos` numa transação única | `lotes-por-ciclo` (500) nas duas varreduras; DLQ em fatias de 100 | corrigido |
| 40 | Pool do Hikari no default (10) sem detecção de vazamento, com duas conexões ociosas presas pelos advisory locks | `maximum-pool-size` 20 e `leak-detection-threshold` explícitos | corrigido |
| 41 | `docs/faq.md` novo contradizia o `docs/faq-arquitetura.md` e o código em sete pontos — "nada interno pode derrubar", "B3 é o mesmo que trocar o IP de um endpoint" — e era o link do topo do README | perguntas úteis absorvidas no FAQ honesto com limite honesto; `docs/faq.md` removido | corrigido |

Cobertura nova: `HardeningQuartoRoundTest` (8 testes), **cada um rodado contra o
código anterior para confirmar que falhava** — 6 de 8 falharam, 2 são guardas de
regressão. Suíte: 36 testes.

## Dívida registrada (não implementada agora)

- Poison-pill: bisect de lote com item ruim (reenvia o lote todo hoje).
- `StateMachine.transicionar` usada só no teste; guardas de produção são strings SQL (3 não usam `origens()`).
- Cancelamento/alteração de duplicata (precisa PATCH/inativar da CERC).
- Timestamp/nonce no HMAC do webhook.
- `consultas-limite` é global; precisa ser por registradora e calibrado contra SLA
  (ver FAQ 3.5).
- Autenticação da DLQ é chave estática, sem identidade nem auditoria (FAQ 7.1).
- Alerta de fila não separa "pendente agora" de "pendente reagendado", então toca
  a noite toda na janela fechada (FAQ 8.1).
- Teto de taxa conta requisições HTTP, não operações (FAQ 4.1).
- `CredenciaisGuard` sem teste.
