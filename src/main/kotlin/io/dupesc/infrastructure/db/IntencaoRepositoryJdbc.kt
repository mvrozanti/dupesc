package io.dupesc.infrastructure.db

import io.dupesc.domain.repository.IntencaoRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class IntencaoRepositoryJdbc(private val jdbc: JdbcClient) : IntencaoRepository {

    override fun inserirSeNovo(operacaoLegadoId: String, duplicataId: Long, dadosJson: String): Long? =
        jdbc.sql(
            """
            INSERT INTO intencao (operacao_legado_id, duplicata_id, dados)
            VALUES (:legado, :duplicata, CAST(:dados AS jsonb))
            ON CONFLICT (operacao_legado_id) DO NOTHING
            RETURNING id
            """.trimIndent(),
        )
            .param("legado", operacaoLegadoId)
            .param("duplicata", duplicataId)
            .param("dados", dadosJson)
            .query(Long::class.java)
            .optional()
            .orElse(null)
}
