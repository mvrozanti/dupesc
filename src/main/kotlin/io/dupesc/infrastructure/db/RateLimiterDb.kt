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
        val nome = registradora.uppercase()
        val rps = properties.cerc.rateLimitRps.toDouble()
        jdbc.sql("INSERT INTO rate_limit (registradora, tokens) VALUES (:r, :rps) ON CONFLICT (registradora) DO NOTHING")
            .param("r", nome)
            .param("rps", rps)
            .update()
        return jdbc.sql(
            """
            UPDATE rate_limit SET
                tokens = LEAST(:rps, tokens + extract(epoch FROM (clock_timestamp() - atualizado_em)) * :rps) - 1,
                atualizado_em = clock_timestamp()
            WHERE registradora = :r
              AND LEAST(:rps, tokens + extract(epoch FROM (clock_timestamp() - atualizado_em)) * :rps) >= 1
            """.trimIndent(),
        )
            .param("rps", rps)
            .param("r", nome)
            .update() == 1
    }
}
