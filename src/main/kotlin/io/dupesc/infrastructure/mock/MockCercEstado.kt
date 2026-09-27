package io.dupesc.infrastructure.mock

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.infrastructure.cerc.ErroLote
import io.dupesc.infrastructure.cerc.ItemInvalidoLote
import io.dupesc.infrastructure.cerc.ItemProcessadoLote
import io.dupesc.infrastructure.cerc.StatusLote
import io.dupesc.infrastructure.configuration.DupeProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import jakarta.annotation.PreDestroy
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class ModoMock { NORMAL, PROCESSANDO_ETERNO, FORA_JANELA, LIMITE_TAXA, HANG_STATUS }

data class ModoRequest(val modo: String, val referenciaExterna: String? = null)

data class ReplayRequest(val eventId: String, val vezes: Int)

data class LoteVisual(
    val id: String,
    val status: String,
    val itens: List<Map<String, Any?>>,
    val itensInvalidos: List<Map<String, Any?>>,
)

@Service
@Profile("mock-cerc")
class MockCercEstado(
    properties: DupeProperties,
    private val objectMapper: ObjectMapper,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(MockCercEstado::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "mock-cerc").apply { isDaemon = true }
    }
    private val webhookPool = Executors.newCachedThreadPool {
        Thread(it, "mock-cerc-webhook").apply { isDaemon = true }
    }
    private val secret = properties.cerc.webhookSecret
    private val destinos = properties.mock.webhookDestino
    private val client = builder.build()
    private val contador = AtomicInteger()
    private val lotes = ConcurrentHashMap<String, LoteMock>()
    private val webhooksEnviados = ConcurrentHashMap<String, String>()
    private val destinoAtual = AtomicInteger()

    @Volatile
    var modo: ModoMock = ModoMock.NORMAL

    @Volatile
    var referenciaRejeitada: String? = null

    fun criarLote(itens: List<Map<String, Any?>>): String {
        val numero = contador.incrementAndGet()
        val id = "lote-$numero"
        lotes[id] = LoteMock(id, numero, itens, objectMapper)
        scheduler.schedule({ processar(id) }, 2, TimeUnit.SECONDS)
        return id
    }

    fun statusDe(id: String): StatusLote {
        val lote = lotes[id] ?: return StatusLote(id = id, status = "ERRO")
        if (lote.status == "PROCESSANDO" && modo != ModoMock.PROCESSANDO_ETERNO) {
            processar(id)
        }
        return StatusLote(
            id = id,
            status = lote.status,
            lista_itens_processados = lote.processados,
            lista_itens_invalidos = lote.invalidados,
        )
    }

    fun visual(): List<LoteVisual> = lotes.values.map {
        LoteVisual(it.idLote, it.status, it.itens, it.invalidosJson())
    }

    fun replay(eventId: String, vezes: Int) {
        val payload = webhooksEnviados[eventId]
            ?: throw IllegalArgumentException("webhook $eventId nao encontrado")
        repeat(vezes) { enviarWebhook(payload) }
    }

    private fun processar(id: String) {
        val lote = lotes[id] ?: return
        if (lote.status != "PROCESSANDO") return
        if (modo == ModoMock.PROCESSANDO_ETERNO) return
        lote.processar(referenciaRejeitada)
        val payload = lote.webhookPayload()
        webhooksEnviados[lote.eventId] = payload
        enviarWebhook(payload)
        log.info("lote {} PROCESSADO, webhook {}", id, lote.eventId)
    }

    private fun enviarWebhook(payload: String) {
        if (destinos.isEmpty()) return
        val destino = destinos[destinoAtual.getAndIncrement() % destinos.size]
        webhookPool.submit {
            try {
                client.post()
                    .uri("$destino/webhook/cerc")
                    .header("X-Signature", assinar(payload))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity()
            } catch (e: Exception) {
                log.warn("falha ao entregar webhook para {}: {}", destino, e.message)
            }
        }
    }

    private fun assinar(corpo: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(corpo.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
        webhookPool.shutdownNow()
    }
}

private class LoteMock(
    val idLote: String,
    private val numero: Int,
    val itens: List<Map<String, Any?>>,
    private val objectMapper: ObjectMapper,
) {
    var status: String = "PROCESSANDO"
    val processados = mutableListOf<ItemProcessadoLote>()
    val invalidados = mutableListOf<ItemInvalidoLote>()
    val eventId: String = UUID.randomUUID().toString()

    @Synchronized
    fun processar(referenciaRejeitada: String?) {
        itens.forEachIndexed { indice, item ->
            val referencia = item["referencia_externa"] as? String ?: return@forEachIndexed
            if (referencia == referenciaRejeitada) {
                invalidados += ItemInvalidoLote(
                    referencia_externa = referencia,
                    erros = listOf(ErroLote("002.CN-008", "duplicata ja emitida")),
                )
            } else {
                processados += ItemProcessadoLote(
                    referencia_externa = referencia,
                    identificador_item_processado = iud(indice),
                )
            }
        }
        status = "PROCESSADO"
    }

    fun invalidosJson(): List<Map<String, Any?>> = invalidados.map {
        mapOf("referencia_externa" to it.referencia_externa, "erros" to it.erros)
    }

    fun webhookPayload(): String = objectMapper.writeValueAsString(
        mapOf(
            "event_id" to eventId,
            "tipo" to "lote-finalizado",
            "lote_id" to idLote,
            "status" to status,
            "itens_processados" to processados,
            "itens_invalidos" to invalidados,
        ),
    )

    private fun iud(indice: Int): String =
        ("IUD" + numero.toString(36) + "X" + indice.toString(36).padStart(5, '0')).take(20)
}
