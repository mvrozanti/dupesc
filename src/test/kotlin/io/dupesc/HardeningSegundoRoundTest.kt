package io.dupesc

import io.dupesc.application.service.RegistroService
import io.dupesc.domain.repository.OutboxRepository
import io.dupesc.domain.repository.ResultadoTitulo
import io.dupesc.domain.repository.TituloRepository
import io.dupesc.infrastructure.db.AdvisoryLockManager
import io.dupesc.infrastructure.db.RateLimiterDb
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestPropertySource(properties = ["dupe.cerc.rate-limit-rps=2"])
class HardeningSegundoRoundTest : WireMockTestBase() {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var registroService: RegistroService

    @Autowired
    private lateinit var advisoryLockManager: AdvisoryLockManager

    @Autowired
    private lateinit var rateLimiterDb: RateLimiterDb

    @Autowired
    private lateinit var tituloRepository: TituloRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun preparar() {
        limparStubs()
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq, rate_limit")
    }

    @Test
    fun `advisory lock nao vaza quando o bloco lanca`() {
        assertThatThrownBy { advisoryLockManager.comLock(LOCK_TESTE) { throw IllegalStateException("estourou") } }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(advisoryLocksAbertos()).isZero()
        assertThat(advisoryLockManager.comLock(LOCK_TESTE) { "reentrou" }).isEqualTo("reentrou")
        assertThat(advisoryLocksAbertos()).isZero()
    }

    @Test
    fun `advisory lock exclui o segundo tomador enquanto esta preso`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val concorrente = advisoryLockManager.comLock(LOCK_TESTE) {
                executor.submit { advisoryLockManager.comLock(LOCK_TESTE) { "nao deveria" } }.get(10, TimeUnit.SECONDS)
            }
            assertThat(concorrente).isNull()
        } finally {
            executor.shutdownNow()
        }
        assertThat(advisoryLocksAbertos()).isZero()
    }

    @Test
    fun `token bucket e global e nao consome permissao quando nega`() {
        assertThat(rateLimiterDb.adquirirPermissao("CERC")).isTrue()
        assertThat(rateLimiterDb.adquirirPermissao("CERC")).isTrue()
        assertThat(rateLimiterDb.adquirirPermissao("CERC")).isFalse()

        val tokens = jdbcTemplate.queryForObject("SELECT tokens FROM rate_limit", Double::class.java)!!
        assertThat(rateLimiterDb.adquirirPermissao("CERC")).isFalse()
        assertThat(jdbcTemplate.queryForObject("SELECT tokens FROM rate_limit", Double::class.java))
            .isEqualTo(tokens)
    }

    @Test
    fun `token bucket recarrega com o tempo`() {
        repeat(3) { rateLimiterDb.adquirirPermissao("CERC") }
        jdbcTemplate.update("UPDATE rate_limit SET atualizado_em = now() - interval '2 seconds'")
        assertThat(rateLimiterDb.adquirirPermissao("CERC")).isTrue()
    }

    @Test
    fun `lote_id divergente descarta so o item e preserva o dedup do evento`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()

        val corpo = webhookProcessado("ev-divergente", loteId = "lote-outro")
        assertThat(enviarWebhook(corpo).statusCode.is2xxSuccessful).isTrue()

        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)).isEqualTo("ENVIADO")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM eventos_recebidos", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isEqualTo(1)

        assertThat(enviarWebhook(corpo).statusCode.is2xxSuccessful).isTrue()
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `mesma duplicata em duas registradoras guarda os dois titulos com um so ativo`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()
        val operacaoId = jdbcTemplate.queryForObject("SELECT id FROM operacao", Long::class.java)!!

        assertThat(tituloRepository.inserirSeNovo("IUD-CERC-1", 0L, operacaoId, "CERC"))
            .isEqualTo(ResultadoTitulo.NOVO)
        assertThat(tituloRepository.inserirSeNovo("IUD-CERC-1", 0L, operacaoId, "CERC"))
            .isEqualTo(ResultadoTitulo.JA_EXISTE)
        assertThat(tituloRepository.inserirSeNovo("IUD-B3-1", 0L, operacaoId, "B3"))
            .isEqualTo(ResultadoTitulo.CONFLITO_ATIVO)

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM titulo", Int::class.java)).isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM titulo WHERE ativo", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT registradora FROM titulo WHERE ativo", String::class.java))
            .isEqualTo("CERC")
    }

    @Test
    fun `registro duplicado detectado pelo webhook vira incidente na dlq`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        registroService.processarLote()
        val operacaoId = jdbcTemplate.queryForObject("SELECT id FROM operacao", Long::class.java)!!
        tituloRepository.inserirSeNovo("IUD-DE-OUTRO-FLUXO", 0L, operacaoId, "B3")

        val corpo = webhookProcessado("ev-duplicado", iud = "IUD000000000000000042")
        assertThat(enviarWebhook(corpo).statusCode.is2xxSuccessful).isTrue()

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM titulo WHERE ativo", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT erro FROM dlq", String::class.java))
            .contains("ja tem titulo ativo")
    }

    @Test
    fun `idade da fila conta do reenfileiramento e nao do criado_em original`() {
        stubLoteCerc("lote-1")
        push(listOf(0L))
        val operacaoId = jdbcTemplate.queryForObject("SELECT id FROM operacao", Long::class.java)!!
        jdbcTemplate.update("UPDATE outbox SET criado_em = now() - interval '30 days', status = 'DLQ'")

        assertThat(outboxRepository.idadePendenteMaisAntigoMs()).isNull()
        assertThat(outboxRepository.reprocessar(operacaoId)).isTrue()

        assertThat(outboxRepository.idadePendenteMaisAntigoMs()!!).isLessThan(60_000L)
    }

    private fun advisoryLocksAbertos() =
        jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'", Int::class.java)

    private fun enviarWebhook(corpo: String) =
        rest.postForEntity(
            "/webhook/cerc",
            HttpEntity(
                corpo,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    set("X-Signature", assinar(corpo))
                },
            ),
            Void::class.java,
        )

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

    companion object {
        private const val LOCK_TESTE = 999001L
    }
}
