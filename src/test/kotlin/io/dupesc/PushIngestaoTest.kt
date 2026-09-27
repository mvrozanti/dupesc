package io.dupesc

import io.dupesc.application.service.RegistroService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate

class PushIngestaoTest : WireMockTestBase() {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var registroService: RegistroService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun preparar() {
        limparStubs()
        stubLoteCerc("lote-1")
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq")
    }

    @Test
    fun `push de novas duplicatas entra no mesmo pipeline do leitor`() {
        val resposta = push(listOf(0L, 1L))
        assertThat(resposta.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resposta.body).isEqualTo(mapOf("novas" to 2, "ja_existentes" to 0))
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intencao", Int::class.java)).isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Int::class.java)).isEqualTo(2)

        registroService.processarLote()

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM operacao WHERE estado = 'ENVIADO'", Int::class.java))
            .isEqualTo(2)
    }

    @Test
    fun `reenvio do mesmo lote nao duplica`() {
        push(listOf(0L))
        val repetido = push(listOf(0L))
        assertThat(repetido.body).isEqualTo(mapOf("novas" to 0, "ja_existentes" to 1))
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intencao", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `sem chave de api nada e gravado`() {
        val corpo = corpoLegado(listOf(0L))
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        val resposta = rest.postForEntity("/api/legado/operacoes", HttpEntity(corpo, headers), Map::class.java)
        assertThat(resposta.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intencao", Int::class.java)).isZero()
    }

    private fun push(ids: List<Long>) =
        rest.postForEntity(
            "/api/legado/operacoes",
            HttpEntity(corpoLegado(ids), HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-Api-Key", "poc-legado")
            }),
            Map::class.java,
        )
}
