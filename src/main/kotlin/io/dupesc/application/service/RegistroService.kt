package io.dupesc.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.domain.model.RegistroComando
import io.dupesc.domain.port.RegistradoraException
import io.dupesc.domain.repository.Claim
import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.ItemComando
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OrigemDlq
import io.dupesc.domain.repository.OutboxRepository
import io.dupesc.domain.service.Canonicalizador
import io.dupesc.domain.service.DadoInvalidoException
import io.dupesc.domain.service.RetryPolicy
import io.dupesc.infrastructure.configuration.DupeProperties
import io.dupesc.infrastructure.db.RateLimiterDb
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class RegistroService(
    private val outboxRepository: OutboxRepository,
    private val operacaoRepository: OperacaoRepository,
    private val dlqRepository: DlqRepository,
    private val registry: RegistradoraRegistry,
    private val retryPolicy: RetryPolicy,
    private val rateLimiterDb: RateLimiterDb,
    private val properties: DupeProperties,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(RegistroService::class.java)

    fun processarLote() {
        val claims = transactionTemplate.execute {
            outboxRepository.reivindicar(properties.worker.batch, properties.podId, properties.worker.leaseMs)
        } ?: return
        if (claims.isEmpty()) return

        val itens = outboxRepository.buscarComandos(claims.map { it.operacaoId })
        val claimsPorId = claims.associateBy { it.operacaoId }
        itens.groupBy { it.registradora.uppercase() }.forEach { (registradora, grupo) ->
            processarGrupo(grupo, registradora, claimsPorId)
        }
    }

    private fun processarGrupo(itens: List<ItemComando>, registradora: String, claimsPorId: Map<Long, Claim>) {
        val validos = mutableListOf<Pair<Long, RegistroComando>>()
        itens.forEach { item ->
            try {
                validos += item.operacaoId to Canonicalizador.de(item.operacaoLegado, item.referenciaExterna)
            } catch (e: DadoInvalidoException) {
                falhaPermanente(listOf(item.operacaoId), "validacao: ${e.message}")
            }
        }
        if (validos.isEmpty()) return
        enviar(validos, registradora, claimsPorId, prazo(validos, claimsPorId))
    }

    private fun prazo(validos: List<Pair<Long, RegistroComando>>, claimsPorId: Map<Long, Claim>): Instant {
        val fim = validos.mapNotNull { claimsPorId[it.first]?.claimedUntil }.minOrNull() ?: return Instant.MAX
        val margem = (properties.worker.leaseMs * MARGEM_LEASE).toLong()
        return fim.minusMillis(margem)
    }

    private fun enviar(
        validos: List<Pair<Long, RegistroComando>>,
        registradora: String,
        claimsPorId: Map<Long, Claim>,
        prazo: Instant,
    ) {
        if (Instant.now().isAfter(prazo)) {
            log.warn(
                "lease perto de vencer — {} operacoes devolvidas a fila sem enviar (pod {})",
                validos.size, properties.podId,
            )
            tratarFalhaEnvio(
                validos,
                claimsPorId,
                RegistradoraException("lease insuficiente para concluir o envio", retryavel = true, naoEsgota = true),
            )
            return
        }

        if (!rateLimiterDb.adquirirPermissao(registradora)) {
            tratarFalhaEnvio(validos, claimsPorId, RegistradoraException("limite de taxa global", retryavel = true, naoEsgota = true))
            return
        }

        try {
            val handle = registry.port(registradora).enviar(validos.map { it.second })
            transactionTemplate.executeWithoutResult {
                operacaoRepository.registrarLote(validos.map { it.first }, handle.id)
            }
            transactionTemplate.executeWithoutResult {
                operacaoRepository.marcarEnviado(validos.map { it.first }, handle.id)
                outboxRepository.marcarProcessado(validos.map { it.first })
            }
            log.info("lote {} enviado com {} operacoes para {} pelo pod {}", handle.id, validos.size, registradora, properties.podId)
        } catch (e: RegistradoraException) {
            if (e.rejeicaoDeConteudo && validos.size > 1) {
                bissectar(validos, registradora, claimsPorId, prazo, e)
                return
            }
            tratarFalhaEnvio(validos, claimsPorId, e)
        }
    }

    private fun bissectar(
        validos: List<Pair<Long, RegistroComando>>,
        registradora: String,
        claimsPorId: Map<Long, Claim>,
        prazo: Instant,
        e: RegistradoraException,
    ) {
        val meio = validos.size / 2
        log.warn(
            "lote de {} rejeitado ({}) — bissectando em {} e {} para isolar o item ruim",
            validos.size, e.message, meio, validos.size - meio,
        )
        enviar(validos.take(meio), registradora, claimsPorId, prazo)
        enviar(validos.drop(meio), registradora, claimsPorId, prazo)
    }

    private fun tratarFalhaEnvio(
        validos: List<Pair<Long, RegistroComando>>,
        claimsPorId: Map<Long, Claim>,
        e: RegistradoraException,
    ) {
        val ids = validos.map { it.first }
        val payloads = validos.associate { (id, comando) -> id to objectMapper.writeValueAsString(comando) }
        val mensagem = e.message ?: "erro de envio"

        if (!e.retryavel) {
            permanente(ids, payloads, mensagem)
            return
        }

        val esgotados = if (e.naoEsgota) {
            emptyList()
        } else {
            ids.filter { (claimsPorId[it]?.attemptCount ?: 0) >= retryPolicy.maxAttempts }
        }
        val retryaveis = ids.filterNot { it in esgotados }
        transactionTemplate.executeWithoutResult {
            if (retryaveis.isNotEmpty() && e.naoEsgota) {
                outboxRepository.falhaRetryavel(retryaveis, retryPolicy.atrasoEspera(), mensagem)
                operacaoRepository.falhaRetryavel(retryaveis, mensagem)
            } else if (retryaveis.isNotEmpty()) {
                retryaveis.groupBy { claimsPorId[it]?.attemptCount ?: 1 }.forEach { (attempt, grupo) ->
                    val atraso = retryPolicy.atrasoMs(attempt)
                    outboxRepository.falhaRetryavel(grupo, atraso, mensagem)
                    operacaoRepository.falhaRetryavel(grupo, mensagem)
                }
            }
            if (esgotados.isNotEmpty()) {
                operacaoRepository.falhaPermanente(esgotados, mensagem)
                outboxRepository.marcarDlq(esgotados)
                esgotados.forEach { id ->
                    dlqRepository.inserir(OrigemDlq.OUTBOX, id, payloads[id] ?: "{}", "tentativas esgotadas: $mensagem")
                }
            }
        }
        log.warn("envio falhou: {} retryaveis, {} na dlq — {}", retryaveis.size, esgotados.size, mensagem)
    }

    private fun permanente(ids: List<Long>, payloads: Map<Long, String>, erro: String) {
        transactionTemplate.executeWithoutResult {
            operacaoRepository.falhaPermanente(ids, erro)
            outboxRepository.marcarDlq(ids)
            ids.forEach { id -> dlqRepository.inserir(OrigemDlq.OUTBOX, id, payloads[id] ?: "{}", erro) }
        }
        log.warn("{} operacoes para DLQ (erro permanente): {}", ids.size, erro)
    }

    private companion object {
        const val MARGEM_LEASE = 0.25
    }

    private fun falhaPermanente(ids: List<Long>, erro: String) {
        transactionTemplate.executeWithoutResult {
            operacaoRepository.falhaPermanente(ids, erro)
            outboxRepository.marcarDlq(ids)
            ids.forEach { id -> dlqRepository.inserir(OrigemDlq.OUTBOX, id, "{}", erro) }
        }
        log.warn("{} operacoes para DLQ: {}", ids.size, erro)
    }
}
