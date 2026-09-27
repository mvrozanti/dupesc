package io.dupesc


import io.dupesc.application.service.LeitorService
import io.dupesc.application.service.ReconciliacaoService
import io.dupesc.application.service.RegistroService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

class EscrituracaoE2ETest : WireMockTestBase() {

    @Autowired
    private lateinit var leitorService: LeitorService

    @Autowired
    private lateinit var registroService: RegistroService

    @Autowired
    private lateinit var reconciliacaoService: ReconciliacaoService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun limpar() {
        limparStubs()
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq")
        stubLegadoPagina(1, """{"pagina":1,"tem_mais":false,"operacoes":${corpoLegado(listOf(0))}}""")
        stubLoteCerc("lote-1")
        stubStatusLote("PROCESSADO", """[{"referencia_externa":"DU-OP-0","identificador_item_processado":"IUD000000000000000001"}]""")
    }

    @Test
    fun `uma operacao do legado vira duplicata registrada em uma registradora`() {
        leitorService.drenar()
        registroService.processarLote()
        reconciliacaoService.executar()

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intencao", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox", String::class.java)).isEqualTo("PROCESSADO")
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)).isEqualTo("REGISTRADO")
        assertThat(jdbcTemplate.queryForObject("SELECT operation_id FROM operacao", String::class.java))
            .isEqualTo("IUD000000000000000001")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM titulo", Int::class.java)).isEqualTo(1)

        val postLote = wireMock.getAllServeEvents().count { it.request.url == "/v2/lote/escrituracao/duplicata/fatura" }
        val getStatus = wireMock.getAllServeEvents().count { it.request.url == "/v2/lote/lote-1/status" }
        assertThat(postLote).isEqualTo(1)
        assertThat(getStatus).isEqualTo(1)
    }
}
