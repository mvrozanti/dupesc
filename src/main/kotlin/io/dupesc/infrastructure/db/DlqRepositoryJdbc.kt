package io.dupesc.infrastructure.db

import io.dupesc.domain.repository.DlqLinha
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

    override fun listarAbertos(limite: Int): List<DlqLinha> =
        jdbc.sql(
            "SELECT id, origem, referencia, payload::text AS payload, erro FROM dlq WHERE status = 'ABERTO' ORDER BY id LIMIT :limite",
        )
            .param("limite", limite)
            .query { rs, _ -> DlqLinha(
                id = rs.getLong("id"),
                origem = rs.getString("origem"),
                referencia = rs.getLong("referencia")?.takeIf { !rs.wasNull() },
                payloadJson = rs.getString("payload"),
                erro = rs.getString("erro"),
            ) }
            .list()

    override fun buscar(id: Long): DlqLinha? =
        jdbc.sql(
            "SELECT id, origem, referencia, payload::text AS payload, erro FROM dlq WHERE id = :id",
        )
            .param("id", id)
            .query { rs, _ -> DlqLinha(
                id = rs.getLong("id"),
                origem = rs.getString("origem"),
                referencia = rs.getLong("referencia")?.takeIf { !rs.wasNull() },
                payloadJson = rs.getString("payload"),
                erro = rs.getString("erro"),
            ) }
            .optional()
            .orElse(null)

    override fun marcarStatus(id: Long, status: String) {
        jdbc.sql("UPDATE dlq SET status = :status, reprocessado_em = now() WHERE id = :id")
            .param("status", status)
            .param("id", id)
            .update()
    }
}
