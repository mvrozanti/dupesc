package io.dupesc


import io.dupesc.application.service.LeitorService
import io.dupesc.application.service.RegistroService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@TestPropertySource(properties = ["dupe.worker.batch=20"])
class ConcorrenciaSemDuplicatasTest : WireMockTestBase() {

    @Autowired
    private lateinit var leitorService: LeitorService

    @Autowired
    private lateinit var registroService: RegistroService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun prepararFilaCom50() {
        limparStubs()
        stubLegadoPagina(1, """{"pagina":1,"tem_mais":false,"operacoes":${corpoLegado((0L until 50L).toList())}}""")
        stubLoteCerc("lote-1")
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq")
        leitorService.drenar()
    }

    @Test
    fun `dois workers simultaneos nao duplicam nenhuma operacao`() {
        val pool = Executors.newFixedThreadPool(2)
        val partida = CountDownLatch(1)
        val ativas = AtomicBoolean(true)

        repeat(2) {
            pool.submit {
                partida.await()
                while (ativas.get()) {
                    registroService.processarLote()
                }
            }
        }
        partida.countDown()

        val concluido = aguardar { pendentes() == 0L }
        assertThat(concluido).isTrue()
        ativas.set(false)
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM operacao WHERE estado = 'ENVIADO'", Long::class.java))
            .isEqualTo(50)

        val corpos = wireMock.getAllServeEvents()
            .filter { it.request.url == "/v2/lote/escrituracao/duplicata/fatura" }
            .map { it.request.bodyAsString }
        val referenciasEnviadas = corpos.flatMap { corpo ->
            Regex(""""referencia_externa":"([^"]+)"""").findAll(corpo).map { it.groupValues[1] }.toList()
        }
        assertThat(referenciasEnviadas.distinct()).hasSize(50)
        assertThat(referenciasEnviadas).hasSize(50)
        corpos.forEach { corpo ->
            val refsNoLote = Regex(""""referencia_externa":"([^"]+)"""").findAll(corpo).count()
            assertThat(refsNoLote).isLessThanOrEqualTo(20)
        }
    }

    private fun pendentes(): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox o JOIN operacao op ON op.id = o.operacao_id WHERE o.status = 'PENDENTE' AND op.estado = 'PENDENTE'",
            Long::class.java,
        )!!

    private fun aguardar(condicao: () -> Boolean): Boolean {
        val limite = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < limite) {
            if (condicao()) return true
            Thread.sleep(100)
        }
        return false
    }
}
