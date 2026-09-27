package io.dupesc

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.BeforeAll
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@TestPropertySource(
    properties = [
        "dupe.retry.base-ms=50",
        "dupe.retry.cap-ms=200",
        "dupe.retry.max-attempts=5",
        "dupe.reconciliacao.idade-min-ms=0",
    ],
)
abstract class WireMockTestBase : PostgresTestBase() {

    companion object {
        @JvmField
        val wireMock: WireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @DynamicPropertySource
        fun registradoras(registry: DynamicPropertyRegistry) {
            registry.add("dupe.cerc.base-url") { "http://localhost:${wireMock.port()}" }
            registry.add("dupe.cerc.token-url") { "http://localhost:${wireMock.port()}/oauth/token" }
            registry.add("dupe.legado.base-url") { "http://localhost:${wireMock.port()}" }
        }

        @JvmStatic
        @BeforeAll
        fun startWireMock() {
            if (!wireMock.isRunning) {
                wireMock.start()
                stubToken()
            }
        }

        fun stubToken() {
            wireMock.stubFor(
                post(urlPathEqualTo("/oauth/token"))
                    .willReturn(
                        aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"access_token":"poc-token","expires_in":3600}"""),
                    ),
            )
        }
    }

    protected fun limparStubs() {
        wireMock.resetAll()
        stubToken()
    }

    protected fun stubLegadoApos(cursor: Long, corpo: String) {
        wireMock.stubFor(
            get(urlPathEqualTo("/legado/operacoes"))
                .withQueryParam("after_id", equalTo("$cursor"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(corpo)),
        )
    }

    protected fun pagina(proximoCursor: Long?, temMais: Boolean, ids: List<Long>): String =
        """{"operacoes":${corpoLegado(ids)}, "proximo_cursor":${proximoCursor ?: "null"}, "tem_mais":$temMais}"""

    protected fun stubLoteCerc(id: String) {
        wireMock.stubFor(
            post(urlPathEqualTo("/v2/lote/escrituracao/duplicata/fatura"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"$id"}"""),
                ),
        )
    }

    protected fun stubStatusLote(status: String, itens: String) {
        wireMock.stubFor(
            get(urlPathMatching("/v2/lote/.*/status"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"lote-1","status":"$status","lista_itens_processados":$itens,"lista_itens_invalidos":[]}""",
                        ),
                ),
        )
    }

    protected fun corpoLegado(ids: List<Long>): String =
        ids.joinToString(prefix = "[", postfix = "]") { id ->
            """{"id":"OP-$id","duplicata_id":$id,"emissao":"2026-01-01","vencimento":"2026-02-01","valor":1000.00,""" +
                """"sacador_documento":"31619393000140","sacador_nome":"FIDC Gestora Ltda",""" +
                """"sacado_documento":"39053344705","sacado_nome":"Sacado $id","sacado_email":"s$id@example.com",""" +
                """"informacoes_pagamento":{"tipo_instrumento":"PPIX","chave_pix":"s$id@example.com"}}"""
        }

    protected fun webhookProcessado(
        eventId: String,
        loteId: String = "lote-1",
        referencia: String = "DU-OP-0",
        iud: String = "IUD000000000000000001",
    ): String =
        """{"event_id":"$eventId","tipo":"lote-finalizado","lote_id":"$loteId","status":"PROCESSADO",""" +
            """"itens_processados":[{"referencia_externa":"$referencia","identificador_item_processado":"$iud"}],""" +
            """"itens_invalidos":[]}"""

    protected fun assinar(corpo: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("poc-secret".toByteArray(), "HmacSHA256"))
        return mac.doFinal(corpo.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
