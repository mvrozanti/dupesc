DROP TABLE rate_limit;

CREATE TABLE rate_limit (
    registradora  TEXT PRIMARY KEY,
    tokens        DOUBLE PRECISION NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
