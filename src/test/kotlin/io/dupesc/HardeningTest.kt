package io.dupesc

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.dupesc.application.service.ReconciliacaoService
import io.dupesc.application.service.RegistroService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["dupe.reconciliacao.consultas-limite=2"])
class HardeningTest : WireMockTestBase() {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var registroService: RegistroService

    @Autowired
    private lateinit var reconciliacaoService: ReconciliacaoService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun preparar() {
        limparStubs()
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq, rate_limit")
    }

    @Test
    fun `dlq nao responde sem chave de api`() {
        val semChave = rest.getForEntity("/api/dlq", String::class.java)
        assertThat(semChave.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val chaveErrada = rest.exchange(
            "/api/dlq",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("X-Api-Key", "chave-errada") }),
            String::class.java,
        )
        assertThat(chaveErrada.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        assertThat(listarDlq().statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `reprocessar sem chave de api nao altera estado`() {
        val resposta = rest.postForEntity("/api/dlq/1/reprocessar", HttpEntity<Void>(HttpHeaders()), String::class.java)
        assertThat(resposta.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `consulta que falha nao consome o orcamento de consultas`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()
        assertThat(consultas()).isZero()

        stubStatusComCodigo(503)
        repeat(4) { reconciliacaoService.executar() }

        assertThat(consultas()).isZero()
        assertThat(estado()).isEqualTo("ENVIADO")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isZero()
    }

    @Test
    fun `preso em processamento vira INDETERMINADO e nao FALHA_PERMANENTE`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()

        stubStatusLoteProcessando()
        repeat(3) { reconciliacaoService.executar() }

        assertThat(estado()).isEqualTo("INDETERMINADO")
        val dlq = jdbcTemplate.queryForMap("SELECT referencia, payload::text AS payload, erro FROM dlq")
        assertThat(dlq["referencia"]).isNotNull()
        assertThat(dlq["payload"].toString()).contains("DU-OP-0").contains("lote-1")
    }

    @Test
    fun `indeterminado nao reprocessa e nao deixa a operacao orfa`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()
        stubStatusLoteProcessando()
        repeat(3) { reconciliacaoService.executar() }
        assertThat(estado()).isEqualTo("INDETERMINADO")
        val dlqId = jdbcTemplate.queryForObject("SELECT id FROM dlq", Long::class.java)!!

        val resposta = rest.exchange(
            "/api/dlq/$dlqId/reprocessar",
            HttpMethod.POST,
            HttpEntity<Void>(cabecalhoAdmin()),
            String::class.java,
        )

        assertThat(resposta.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(estado()).isEqualTo("INDETERMINADO")
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox", String::class.java)).isEqualTo("PROCESSADO")
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM dlq", String::class.java)).isEqualTo("ABERTO")
    }

    @Test
    fun `lote rejeitado e bissectado para isolar so o item ruim`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/v2/lote/escrituracao/duplicata/fatura"))
                .atPriority(1)
                .withRequestBody(containing("DU-OP-3"))
                .willReturn(aResponse().withStatus(400)),
        )
        wireMock.stubFor(
            post(urlPathEqualTo("/v2/lote/escrituracao/duplicata/fatura"))
                .atPriority(5)
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"lote-ok"}"""),
                ),
        )

        push(listOf(0L, 1L, 2L, 3L))
        registroService.processarLote()

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM operacao WHERE estado = 'ENVIADO'", Int::class.java))
            .isEqualTo(3)
        val condenadas = jdbcTemplate.queryForList(
            "SELECT referencia_externa FROM operacao WHERE estado = 'FALHA_PERMANENTE'",
            String::class.java,
        )
        assertThat(condenadas).containsExactly("DU-OP-3")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `rota errada na registradora nao despeja a fila na dlq`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/v2/lote/escrituracao/duplicata/fatura"))
                .willReturn(aResponse().withStatus(404)),
        )

        push(listOf(0L, 1L))
        registroService.processarLote()

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE status = 'PENDENTE'", Int::class.java))
            .isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM operacao WHERE estado = 'PENDENTE'", Int::class.java))
            .isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject("SELECT min(next_attempt_at) > now() FROM outbox", Boolean::class.java))
            .isTrue()
    }

    private fun estado() = jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)

    private fun consultas() = jdbcTemplate.queryForObject("SELECT consultas FROM operacao", Int::class.java)

    private fun cabecalhoAdmin() = HttpHeaders().apply { set("X-Api-Key", "poc-admin") }

    private fun listarDlq() =
        rest.exchange("/api/dlq", HttpMethod.GET, HttpEntity<Void>(cabecalhoAdmin()), String::class.java)

    private fun stubStatusComCodigo(codigo: Int) {
        wireMock.stubFor(
            get(urlPathMatching("/v2/lote/.*/status")).willReturn(aResponse().withStatus(codigo)),
        )
    }

    private fun stubStatusLoteProcessando() {
        wireMock.stubFor(
            get(urlPathMatching("/v2/lote/.*/status"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"lote-1","status":"PROCESSANDO","lista_itens_processados":[],"lista_itens_invalidos":[]}""",
                        ),
                ),
        )
    }

    private fun push(ids: List<Long>) =
        rest.postForEntity(
            "/api/legado/operacoes",
            HttpEntity(
                corpoLegado(ids),
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    set("X-Api-Key", "poc-legado")
                },
            ),
            Map::class.java,
        )
}
