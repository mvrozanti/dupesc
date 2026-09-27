package io.dupesc.infrastructure.db

import io.dupesc.infrastructure.configuration.DupeProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

@Component
class RateLimiterDb(
    private val jdbc: JdbcClient,
    private val properties: DupeProperties,
) {

    fun adquirirPermissao(registradora: String): Boolean {
        val rps = properties.cerc.rateLimitRps
        val contador = jdbc.sql(
            """
            INSERT INTO rate_limit (registradora, janela_inicio, contador) VALUES (:r, now(), 1)
            ON CONFLICT (registradora) DO UPDATE
            SET contador = CASE
                    WHEN rate_limit.janela_inicio < now() - interval '1 second' THEN 1
                    ELSE rate_limit.contador + 1
                END,
                janela_inicio = CASE
                    WHEN rate_limit.janela_inicio < now() - interval '1 second' THEN now()
                    ELSE rate_limit.janela_inicio
                END
            RETURNING contador
            """.trimIndent(),
        )
            .param("r", registradora.uppercase())
            .query(Int::class.java)
            .single()!!
        return contador <= rps
    }
}
