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
            tratarFalhaEnvio(validos, claimsPorId, e)
        }
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
            if (retryaveis.isNotEmpty()) {
                val atraso = retryPolicy.atrasoMs(claimsPorId[retryaveis.first()]?.attemptCount ?: 1)
                outboxRepository.falhaRetryavel(retryaveis, atraso, mensagem)
                operacaoRepository.falhaRetryavel(retryaveis, mensagem)
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

    private fun falhaPermanente(ids: List<Long>, erro: String) {
        transactionTemplate.executeWithoutResult {
            operacaoRepository.falhaPermanente(ids, erro)
            outboxRepository.marcarDlq(ids)
            ids.forEach { id -> dlqRepository.inserir(OrigemDlq.OUTBOX, id, "{}", erro) }
        }
        log.warn("{} operacoes para DLQ: {}", ids.size, erro)
    }
}
