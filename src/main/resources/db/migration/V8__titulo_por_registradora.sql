ALTER TABLE titulo ADD COLUMN registradora TEXT NOT NULL DEFAULT 'CERC';
ALTER TABLE titulo ADD COLUMN ativo BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE titulo DROP CONSTRAINT titulo_duplicata_id_key;
ALTER TABLE titulo ADD CONSTRAINT titulo_registradora_duplicata_key UNIQUE (registradora, duplicata_id);

CREATE UNIQUE INDEX idx_titulo_ativo ON titulo (duplicata_id) WHERE ativo;
