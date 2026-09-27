package io.dupesc.infrastructure.db

import io.dupesc.domain.repository.EventoRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class EventoRepositoryJdbc(private val jdbc: JdbcClient) : EventoRepository {

    override fun registrarSeNovo(registradora: String, eventId: String, tipo: String, payloadJson: String): Boolean =
        jdbc.sql(
            """
            INSERT INTO eventos_recebidos (registradora, event_id, tipo, payload)
            VALUES (:registradora, :evento, :tipo, CAST(:payload AS jsonb))
            ON CONFLICT (registradora, event_id) DO NOTHING
            RETURNING id
            """.trimIndent(),
        )
            .param("registradora", registradora)
            .param("evento", eventId)
            .param("tipo", tipo)
            .param("payload", payloadJson)
            .query(Long::class.java)
            .optional()
            .isPresent
}
