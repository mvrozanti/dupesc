ALTER TABLE operacao ADD COLUMN ultimo_erro TEXT;
ALTER TABLE operacao ADD COLUMN consultas INT NOT NULL DEFAULT 0;

CREATE TABLE rate_limit (
    registradora   TEXT PRIMARY KEY,
    janela_inicio  TIMESTAMPTZ NOT NULL DEFAULT now(),
    contador       INT NOT NULL DEFAULT 0
);
