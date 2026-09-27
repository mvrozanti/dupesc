package io.dupesc.infrastructure.db

import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.OrigemDlq
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class DlqRepositoryJdbc(private val jdbc: JdbcClient) : DlqRepository {

    override fun inserir(origem: OrigemDlq, referencia: Long?, payloadJson: String, erro: String) {
        jdbc.sql(
            """
            INSERT INTO dlq (origem, referencia, payload, erro) VALUES (:origem, :referencia, CAST(:payload AS jsonb), :erro)
            """.trimIndent(),
        )
            .param("origem", origem.name)
            .param("referencia", referencia)
            .param("payload", payloadJson)
            .param("erro", erro)
            .update()
    }

    override fun contarAbertos(): Long =
        jdbc.sql("SELECT count(*) FROM dlq WHERE status = 'ABERTO'")
            .query(Long::class.java)
            .single()!!
}
