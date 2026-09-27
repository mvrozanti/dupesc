CREATE TABLE checkpoint (
    nome          TEXT PRIMARY KEY,
    valor         BIGINT NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE intencao (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operacao_legado_id TEXT NOT NULL UNIQUE,
    duplicata_id       BIGINT NOT NULL,
    dados              JSONB NOT NULL,
    criada_em          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE operacao (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    intencao_id        BIGINT NOT NULL UNIQUE REFERENCES intencao(id),
    referencia_externa TEXT NOT NULL UNIQUE,
    operation_id       TEXT UNIQUE,
    lote_id            TEXT,
    estado             TEXT NOT NULL DEFAULT 'PENDENTE'
        CHECK (estado IN ('PENDENTE','EM_ENVIO','ENVIADO','REGISTRADO','RECUSADO','FALHA_PERMANENTE')),
    erros              JSONB,
    enviado_em         TIMESTAMPTZ,
    atualizado_em      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operacao_id     BIGINT NOT NULL UNIQUE REFERENCES operacao(id),
    status          TEXT NOT NULL DEFAULT 'PENDENTE'
        CHECK (status IN ('PENDENTE','EM_ENVIO','PROCESSADO','DLQ')),
    attempt_count   INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by      TEXT,
    claimed_until   TIMESTAMPTZ,
    ultimo_erro     TEXT,
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE titulo (
    iud          TEXT PRIMARY KEY,
    duplicata_id BIGINT NOT NULL UNIQUE,
    operacao_id  BIGINT NOT NULL REFERENCES operacao(id),
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE eventos_recebidos (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    registradora TEXT NOT NULL,
    event_id     TEXT NOT NULL,
    tipo         TEXT NOT NULL,
    payload      JSONB NOT NULL,
    recebido_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (registradora, event_id)
);

CREATE TABLE dlq (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    origem          TEXT NOT NULL CHECK (origem IN ('OUTBOX','WEBHOOK','LEITOR')),
    referencia      BIGINT,
    payload         JSONB NOT NULL,
    erro            TEXT NOT NULL,
    tentativas      INT NOT NULL DEFAULT 0,
    status          TEXT NOT NULL DEFAULT 'ABERTO'
        CHECK (status IN ('ABERTO','REPROCESSADO','DESCARTADO')),
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    reprocessado_em TIMESTAMPTZ
);

CREATE INDEX idx_outbox_fila   ON outbox (next_attempt_at) WHERE status = 'PENDENTE';
CREATE INDEX idx_outbox_lease  ON outbox (claimed_until)   WHERE status = 'EM_ENVIO';
CREATE INDEX idx_operacao_env  ON operacao (enviado_em)    WHERE estado = 'ENVIADO';
CREATE INDEX idx_operacao_lote ON operacao (lote_id)       WHERE lote_id IS NOT NULL;
