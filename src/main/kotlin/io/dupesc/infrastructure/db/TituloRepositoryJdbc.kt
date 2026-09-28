package io.dupesc.infrastructure.db

import io.dupesc.domain.repository.ResultadoTitulo
import io.dupesc.domain.repository.TituloRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class TituloRepositoryJdbc(private val jdbc: JdbcClient) : TituloRepository {

    override fun inserirSeNovo(
        iud: String,
        duplicataId: Long,
        operacaoId: Long,
        registradora: String,
    ): ResultadoTitulo =
        jdbc.sql(
            """
            INSERT INTO titulo (iud, duplicata_id, operacao_id, registradora, ativo)
            SELECT :iud, :duplicata, :operacao, :registradora,
                   NOT EXISTS (SELECT 1 FROM titulo t WHERE t.duplicata_id = :duplicata AND t.ativo)
            ON CONFLICT (iud) DO NOTHING
            RETURNING ativo
            """.trimIndent(),
        )
            .param("iud", iud)
            .param("duplicata", duplicataId)
            .param("operacao", operacaoId)
            .param("registradora", registradora.uppercase())
            .query(Boolean::class.java)
            .optional()
            .map { if (it) ResultadoTitulo.NOVO else ResultadoTitulo.CONFLITO_ATIVO }
            .orElse(ResultadoTitulo.JA_EXISTE)
}
