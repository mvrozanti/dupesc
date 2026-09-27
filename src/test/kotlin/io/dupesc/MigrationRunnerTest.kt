package io.dupesc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired

class MigrationRunnerTest : PostgresTestBase() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `flyway sobe o schema completo`() {
        val tabelas = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
            String::class.java,
        )
        assertThat(tabelas).contains(
            "checkpoint", "intencao", "operacao", "outbox", "titulo", "eventos_recebidos", "dlq",
        )
    }
}
