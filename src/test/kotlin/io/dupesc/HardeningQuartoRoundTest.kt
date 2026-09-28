package io.dupesc

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.client.WireMock.post
import io.dupesc.application.service.ReconciliacaoService
import io.dupesc.application.service.RegistroService
import io.dupesc.domain.repository.ResultadoTitulo
import io.dupesc.domain.repository.TituloRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean

@TestPropertySource(
    properties = [
        "dupe.reconciliacao.consultas-limite=2",
        "dupe.worker.lease-ms=2000",
        "dupe.retry.base-ms=100",
        "dupe.retry.cap-ms=600000",
    ],
)
class HardeningQuartoRoundTest : WireMockTestBase() {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var registroService: RegistroService

    @Autowired
    private lateinit var reconciliacaoService: ReconciliacaoService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @MockitoSpyBean
    private lateinit var tituloRepository: TituloRepository

    @BeforeEach
    fun preparar() {
        limparStubs()
        Mockito.reset(tituloRepository)
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq, rate_limit")
    }

    @Test
    fun `lote em ERRO na registradora vira INDETERMINADO em vez de reenvio cego`() {
        stubLoteCerc("lote-1")
        push(listOf(0L, 1L))
        registroService.processarLote()
        stubStatus("ERRO")

        reconciliacaoService.executar()

        assertThat(estados()).containsOnly("INDETERMINADO")
        assertThat(jdbcTemplate.queryForList("SELECT status FROM outbox", String::class.java))
            .containsOnly("PROCESSADO")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isEqualTo(2)
    }

    @Test
    fun `operacao indeterminada por lote em ERRO nao reprocessa`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()
        stubStatus("ERRO")
        reconciliacaoService.executar()
        val dlqId = jdbcTemplate.queryForObject("SELECT min(id) FROM dlq", Long::class.java)!!

        val resposta = rest.exchange(
            "/api/dlq/$dlqId/reprocessar",
            HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders().apply { set("X-Api-Key", "poc-admin") }),
            String::class.java,
        )

        assertThat(resposta.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(estados()).containsOnly("INDETERMINADO")
    }

    @Test
    fun `bisect para quando o lease esta perto de vencer`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/v2/lote/escrituracao/duplicata/fatura"))
                .willReturn(aResponse().withStatus(400).withFixedDelay(2000)),
        )

        push(listOf(0L, 1L, 2L, 3L))
        registroService.processarLote()

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v2/lote/escrituracao/duplicata/fatura")))
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isZero()
        assertThat(estados()).containsOnly("PENDENTE")
        assertThat(jdbcTemplate.queryForList("SELECT status FROM outbox", String::class.java))
            .containsOnly("PENDENTE")
    }

    @Test
    fun `erro que nao e rejeicao de conteudo nao bisseca`() {
        stubLoteCerc("lote-1")
        push(listOf(0L, 1L, 2L, 3L))
        jdbcTemplate.update("UPDATE operacao SET registradora = 'DESCONHECIDA'")

        registroService.processarLote()

        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/v2/lote/escrituracao/duplicata/fatura")))
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isEqualTo(4)
        assertThat(estados()).containsOnly("FALHA_PERMANENTE")
        assertThat(permissoesConsumidas()).isEqualTo(1)
    }

    @Test
    fun `espera de janela nao usa backoff exponencial`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/v2/lote/escrituracao/duplicata/fatura"))
                .willReturn(aResponse().withStatus(429)),
        )
        push(listOf(0L))
        jdbcTemplate.update("UPDATE outbox SET attempt_count = 10")

        registroService.processarLote()

        val atrasoMs = jdbcTemplate.queryForObject(
            "SELECT extract(epoch FROM (next_attempt_at - now())) * 1000 FROM outbox",
            Double::class.java,
        )!!
        assertThat(atrasoMs).isLessThan(5_000.0)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isZero()
    }

    @Test
    fun `falha ao aplicar um item nao cancela a passada de reconciliacao`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()
        Mockito.doThrow(DuplicateKeyException("corrida no indice de titulo ativo"))
            .`when`(tituloRepository)
            .inserirSeNovo(Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString())
        stubStatusProcessado()

        reconciliacaoService.executar()

        assertThat(estados()).containsOnly("ENVIADO")
        assertThat(jdbcTemplate.queryForObject("SELECT consultas FROM operacao", Int::class.java)).isZero()

        Mockito.reset(tituloRepository)
        reconciliacaoService.executar()
        assertThat(estados()).containsOnly("REGISTRADO")
    }

    @Test
    fun `conflito de titulo ativo continua sendo tratado sem excecao`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()
        val operacaoId = jdbcTemplate.queryForObject("SELECT id FROM operacao", Long::class.java)!!
        Mockito.reset(tituloRepository)
        tituloRepository.inserirSeNovo("IUD-PREEXISTENTE", 0L, operacaoId, "B3")
        stubStatusProcessado()

        reconciliacaoService.executar()

        assertThat(estados()).containsOnly("REGISTRADO")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM titulo WHERE ativo", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT erro FROM dlq", String::class.java))
            .contains("ja tem titulo ativo")
    }

    @Test
    fun `insercao de titulo continua idempotente por iud`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()
        val operacaoId = jdbcTemplate.queryForObject("SELECT id FROM operacao", Long::class.java)!!
        Mockito.reset(tituloRepository)

        assertThat(tituloRepository.inserirSeNovo("IUD-X", 0L, operacaoId, "CERC")).isEqualTo(ResultadoTitulo.NOVO)
        assertThat(tituloRepository.inserirSeNovo("IUD-X", 0L, operacaoId, "CERC")).isEqualTo(ResultadoTitulo.JA_EXISTE)
    }

    private fun estados() = jdbcTemplate.queryForList("SELECT estado FROM operacao", String::class.java)

    private fun permissoesConsumidas(): Int {
        val rps = 80.0
        val tokens = jdbcTemplate.queryForObject("SELECT tokens FROM rate_limit", Double::class.java) ?: rps
        return Math.round(rps - tokens).toInt()
    }

    private fun stubStatus(status: String) {
        wireMock.stubFor(
            get(urlPathMatching("/v2/lote/.*/status"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"lote-1","status":"$status","lista_itens_processados":[],"lista_itens_invalidos":[]}""",
                        ),
                ),
        )
    }

    private fun stubStatusProcessado() {
        wireMock.stubFor(
            get(urlPathMatching("/v2/lote/.*/status"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"lote-1","status":"PROCESSADO",""" +
                                """"lista_itens_processados":[{"referencia_externa":"DU-OP-0","identificador_item_processado":"IUD000000000000000001"}],""" +
                                """"lista_itens_invalidos":[]}""",
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
