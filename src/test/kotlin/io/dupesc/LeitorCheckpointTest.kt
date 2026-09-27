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
    fun prepararTresPaginas() {
        limparStubs()
        stubLegadoPagina(1, """{"pagina":1,"tem_mais":true,"operacoes":${corpoLegado(listOf(0, 1))}}""")
        stubLegadoPagina(2, """{"pagina":2,"tem_mais":true,"operacoes":${corpoLegado(listOf(2, 3))}}""")
        stubLegadoPagina(3, """{"pagina":3,"tem_mais":false,"operacoes":${corpoLegado(listOf(4))}}""")
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq")
    }

    @Test
    fun `leitor avanca o checkpoint de forma monotona e releitura de pagina nao duplica`() {
        leitorService.drenar()
        assertThat(checkpoint()).isEqualTo(3L)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intencao", Int::class.java)).isEqualTo(5)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Int::class.java)).isEqualTo(5)

        jdbcTemplate.update("UPDATE checkpoint SET valor = 1 WHERE nome = 'legado.pagina'")
        leitorService.drenar()

        assertThat(checkpoint()).isEqualTo(3L)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intencao", Int::class.java)).isEqualTo(5)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Int::class.java)).isEqualTo(5)
    }

    private fun checkpoint(): Long =
        jdbcTemplate.queryForObject("SELECT valor FROM checkpoint WHERE nome = 'legado.pagina'", Long::class.java)!!
}
