package io.dupesc.infrastructure.db

import io.dupesc.domain.repository.TituloRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class TituloRepositoryJdbc(private val jdbc: JdbcClient) : TituloRepository {

    override fun inserirSeNovo(iud: String, duplicataId: Long, operacaoId: Long) {
        jdbc.sql(
            """
            INSERT INTO titulo (iud, duplicata_id, operacao_id) VALUES (:iud, :duplicata, :operacao)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )
            .param("iud", iud)
            .param("duplicata", duplicataId)
            .param("operacao", operacaoId)
            .update()
    }
}
