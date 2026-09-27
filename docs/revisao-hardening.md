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
| 15 | DLQ é write-only — sem listagem nem reprocessamento | `GET /api/dlq` + `POST /api/dlq/{id}/reprocessar` + transições | planejado |
| 16 | Menores: backoff pelo 1º item do lote · case de registradora normalizado 3× · `contarPendentes` no repo errado · worker sem `runCatching` · `DUPE_POD_ID` default compartilhado | corrigir cada um | planejado |

## Dívida registrada (não implementada agora)

- Poison-pill: bisect de lote com item ruim (reenvia o lote todo hoje).
- `StateMachine.transicionar` usada só no teste; guardas de produção são strings SQL (3 não usam `origens()`).
- Cancelamento/alteração de duplicata (precisa PATCH/inativar da CERC).
- Timestamp/nonce no HMAC do webhook.
