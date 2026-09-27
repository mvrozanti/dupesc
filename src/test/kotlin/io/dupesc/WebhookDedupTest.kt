package io.dupesc

import io.dupesc.domain.repository.IntencaoRepository
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OutboxRepository
import io.dupesc.domain.service.ReferenciaExterna
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
import org.springframework.transaction.support.TransactionTemplate

class WebhookDedupTest : WireMockTestBase() {

    @Autowired
    private lateinit var intencaoRepository: IntencaoRepository

    @Autowired
    private lateinit var operacaoRepository: OperacaoRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var rest: TestRestTemplate

    @BeforeEach
    fun prepararEnviado() {
        jdbcTemplate.update("TRUNCATE checkpoint, intencao, operacao, outbox, titulo, eventos_recebidos, dlq")
        transactionTemplate.executeWithoutResult {
            val intencaoId = intencaoRepository.inserirSeNovo("OP-0", 0L, """{"id":"OP-0"}""")!!
            val operacaoId = operacaoRepository.inserir(intencaoId, ReferenciaExterna.de("OP-0"), "CERC")
            outboxRepository.inserir(operacaoId)
            outboxRepository.reivindicar(1, "teste", 60_000)
            operacaoRepository.marcarEnviado(listOf(operacaoId), "lote-1")
            outboxRepository.marcarProcessado(listOf(operacaoId))
        }
    }

    @Test
    fun `webhook repetido 3 vezes processa uma unica vez`() {
        val corpo = webhookProcessado("ev-1")

        repeat(3) {
            val resposta = rest.postForEntity("/webhook/cerc", entidade(corpo), Void::class.java)
            assertThat(resposta.statusCode).isEqualTo(HttpStatus.OK)
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM eventos_recebidos", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)).isEqualTo("REGISTRADO")
        assertThat(jdbcTemplate.queryForObject("SELECT operation_id FROM operacao", String::class.java))
            .isEqualTo("IUD000000000000000001")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM titulo", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `assinatura invalida e rejeitada sem gravar nada`() {
        val corpo = webhookProcessado("ev-2")
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("X-Signature", "assinatura-errada")

        val resposta = rest.postForEntity("/webhook/cerc", HttpEntity(corpo, headers), Void::class.java)

        assertThat(resposta.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM eventos_recebidos", Int::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM operacao", String::class.java)).isEqualTo("ENVIADO")
    }

    private fun entidade(corpo: String): HttpEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("X-Signature", assinar(corpo))
        return HttpEntity(corpo, headers)
    }
}
