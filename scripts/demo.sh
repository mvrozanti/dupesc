#!/usr/bin/env bash
set -euo pipefail

sql() { docker compose exec -T postgres psql -U dupesc -d dupesc -tAc "$1"; }
cerc() { curl -sf -X POST "http://localhost:18081/mock/cerc/$1" -H 'Content-Type: application/json' -d "${2:-{}}"; }
passo() { printf '\n\033[1;36m=== %s ===\033[0m\n' "$1"; }
aguardar() {
    local desc="$1" cond="$2" limite="${3:-180}"
    for _ in $(seq 1 "$limite"); do
        if eval "$cond" >/dev/null 2>&1; then return 0; fi
        sleep 1
    done
    echo "timeout esperando: $desc" >&2
    return 1
}
estados() {
    echo "  estado          | contagem"
    sql "SELECT estado || '          | ' || count(*) FROM operacao GROUP BY estado ORDER BY estado"
}

passo "Subindo stack (postgres + 2 pods + mocks)"
if [ -z "${SKIP_BUILD:-}" ]; then ./gradlew bootJar -q; fi
docker compose down -v 2>/dev/null || true
docker compose up -d --build postgres cerc-mock legado-mock
aguardar "mock legado" "curl -sf 'http://localhost:18082/legado/operacoes?after_id=-1&tamanho=1'" 120

passo "Seed: 1200 operacoes no legado"
curl -sf -X POST http://localhost:18082/mock/legado/reiniciar -H 'Content-Type: application/json' -d '{"total": 1200}' >/dev/null

docker compose up -d --build app-1 app-2
aguardar "app saudavel" "curl -sf http://localhost:18091/actuator/health && curl -sf http://localhost:18092/actuator/health" 240

passo "PROVA 1 — legado vira duplicata registrada (2 pods, zero duplicatas)"
aguardar "1200 registradas" "[ \"\$(sql \"SELECT count(*) FROM operacao WHERE estado='REGISTRADO'\")\" -ge 1200 ]"
estados
echo "  intencoes: $(sql 'SELECT count(*) FROM intencao') | titulos: $(sql 'SELECT count(*) FROM titulo') | webhooks recebidos: $(sql 'SELECT count(*) FROM eventos_recebidos')"

passo "PROVA 4 — webhook repetido 3x processa 1x"
evento="$(sql "SELECT event_id FROM eventos_recebidos LIMIT 1")"
curl -sf -X POST http://localhost:18081/mock/cerc/replay -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$evento\",\"vezes\":3}" >/dev/null
sleep 3
echo "  eventos apos replay: $(sql 'SELECT count(*) FROM eventos_recebidos') (esperado: igual ao anterior)"

passo "PROVA 3 — pod morre no meio do envio, reconciliacao resolve"
cerc modo '{"modo":"processando_eterno"}'
curl -sf -X POST http://localhost:18082/mock/legado/reiniciar -H 'Content-Type: application/json' -d '{"total": 2500}' >/dev/null
aguardar "100 novas ENVIADO" "[ \"\$(sql \"SELECT count(*) FROM operacao WHERE estado='ENVIADO'\")\" -ge 100 ]"
echo "  matando app-1 (o pod que enviou os lotes pendentes)"
docker compose kill app-1
cerc modo '{"modo":"normal"}'
aguardar "2500 registradas so com app-2" "[ \"\$(sql \"SELECT count(*) FROM operacao WHERE estado='REGISTRADO'\")\" -ge 2500 ] 420"
estados
echo "  app-1 continua morto; app-2 reconciliou tudo"

passo "PROVA 5 — nenhuma duplicata entre pods"
echo "  referencia_externa distintas: $(sql 'SELECT count(DISTINCT referencia_externa) FROM operacao')"
echo "  operacoes totais: $(sql 'SELECT count(*) FROM operacao')"
echo "  eventos_recebidos (dedup): $(sql 'SELECT count(*) FROM eventos_recebidos') | titulos: $(sql 'SELECT count(*) FROM titulo')"

passo "DLQ vazia e estados finais"
echo "  dlq: $(sql 'SELECT count(*) FROM dlq')"
estados

passo "Demo concluida. Limpeza com: docker compose down"
