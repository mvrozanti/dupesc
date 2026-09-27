package io.dupesc

import io.dupesc.application.service.LeitorService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

class LeitorCheckpointTest : WireMockTestBase() {

    @Autowired
    private lateinit var leitorService: LeitorService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun preparar() {
        limparStubs()
        stubLegadoApos(-1L, pagina(4L, false, listOf(0, 1, 2, 3, 4)))
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq")
    }

    @Test
    fun `leitor avanca o cursor de forma monotona e releitura nao duplica`() {
        leitorService.drenar()
        assertThat(checkpoint()).isEqualTo(4L)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intencao", Int::class.java)).isEqualTo(5)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Int::class.java)).isEqualTo(5)

        jdbcTemplate.update("UPDATE checkpoint SET valor = -1 WHERE nome = 'legado.cursor'")
        leitorService.drenar()

        assertThat(checkpoint()).isEqualTo(4L)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intencao", Int::class.java)).isEqualTo(5)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Int::class.java)).isEqualTo(5)
    }

    private fun checkpoint(): Long =
        jdbcTemplate.queryForObject("SELECT valor FROM checkpoint WHERE nome = 'legado.cursor'", Long::class.java)!!
}
