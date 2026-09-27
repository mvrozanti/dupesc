package io.dupesc

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.dupesc.application.service.LeitorService
import io.dupesc.application.service.ReconciliacaoService
import io.dupesc.application.service.RegistroService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate

class ReconciliacaoPosQuedaTest : WireMockTestBase() {

    @Autowired
    private lateinit var leitorService: LeitorService

    @Autowired
    private lateinit var registroService: RegistroService

    @Autowired
    private lateinit var reconciliacaoService: ReconciliacaoService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var rest: TestRestTemplate

    @BeforeEach
    fun prepararEnviadoComStatusEterno() {
        limparStubs()
        stubLegadoApos(-1L, pagina(null, false, listOf(0)))
        stubLoteCerc("lote-1")
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
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq")
        leitorService.drenar()
        registroService.processarLote()
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)).isEqualTo("ENVIADO")
    }

    @Test
    fun `se o pod morre depois do envio a reconciliacao resolve sozinha`() {
        wireMock.stubFor(
            get(urlPathMatching("/v2/lote/.*/status"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"lote-1","status":"PROCESSADO",""" +
                                """"lista_itens_processados":[{"referencia_externa":"DU-OP-0","identificador_item_processado":"IUD000000000000000009"}],""" +
                                """"lista_itens_invalidos":[]}""",
                        ),
                ),
        )

        reconciliacaoService.executar()

        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)).isEqualTo("REGISTRADO")
        assertThat(jdbcTemplate.queryForObject("SELECT operation_id FROM operacao", String::class.java))
            .isEqualTo("IUD000000000000000009")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM titulo", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `webhook que chega antes da reconciliacao nao regride nada`() {
        val corpo = webhookProcessado("ev-1", iud = "IUD000000000000000007")
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("X-Signature", assinar(corpo))
        assertThat(rest.postForEntity("/webhook/cerc", HttpEntity(corpo, headers), Void::class.java).statusCode.is2xxSuccessful)
            .isTrue()
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)).isEqualTo("REGISTRADO")

        reconciliacaoService.executar()

        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)).isEqualTo("REGISTRADO")
        assertThat(jdbcTemplate.queryForObject("SELECT operation_id FROM operacao", String::class.java))
            .isEqualTo("IUD000000000000000007")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM titulo", Int::class.java)).isEqualTo(1)
    }
}
