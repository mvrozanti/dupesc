package io.dupesc.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.domain.model.EnvioHandle
import io.dupesc.domain.model.ProcessamentoEstado
import io.dupesc.domain.port.RegistradoraException
import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.LoteEnviado
import io.dupesc.domain.repository.OperacaoLinha
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OrigemDlq
import io.dupesc.domain.repository.OutboxRepository
import io.dupesc.domain.repository.ResultadoTitulo
import io.dupesc.domain.repository.TituloRepository
import io.dupesc.infrastructure.configuration.DupeProperties
import io.dupesc.infrastructure.db.AdvisoryLockManager
import io.dupesc.infrastructure.db.RateLimiterDb
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class ReconciliacaoService(
    private val outboxRepository: OutboxRepository,
    private val operacaoRepository: OperacaoRepository,
    private val tituloRepository: TituloRepository,
    private val dlqRepository: DlqRepository,
    private val registry: RegistradoraRegistry,
    private val rateLimiterDb: RateLimiterDb,
    private val advisoryLockManager: AdvisoryLockManager,
    private val properties: DupeProperties,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(ReconciliacaoService::class.java)

    fun executar(): Int? = advisoryLockManager.comLock(LOCK_RECONCILIADOR) {
        repararLeases()
        reconciliarEnviados()
    }

    private fun repararLeases() {
        transactionTemplate.executeWithoutResult {
            val promovidos = outboxRepository.promoverEnviadosComLote()
            val resetados = outboxRepository.repararLeasesVencidos()
            if (promovidos.isNotEmpty()) log.warn("{} envios concluidos promovidos a ENVIADO", promovidos.size)
            if (resetados.isNotEmpty()) log.warn("{} leases de envio expirados reenfileirados", resetados.size)
        }
    }

    private fun reconciliarEnviados(): Int {
        val idadeMin = Instant.now().minusMillis(properties.reconciliacao.idadeMinMs)
        val lotes = operacaoRepository.buscarLotesEnviados(idadeMin, properties.reconciliacao.lotesPorCiclo)
        var resolvidos = 0
        for (lote in lotes) {
            if (!rateLimiterDb.adquirirPermissao(lote.registradora)) {
                log.info("teto de taxa atingido — {} lotes ficam para o proximo ciclo", lotes.size - resolvidos)
                break
            }
            try {
                val resultado = registry.port(lote.registradora).consultar(EnvioHandle(lote.loteId))
                when (resultado.statusLote) {
                    ProcessamentoEstado.PROCESSANDO -> transactionTemplate.executeWithoutResult {
                        operacaoRepository.incrementarConsultas(lote.operacaoIds)
                    }
                    ProcessamentoEstado.PROCESSADO -> resolvidos += aplicarItens(lote, resultado.itens)
                    ProcessamentoEstado.REJEITADO -> resolvidos += aplicarItens(lote, resultado.itens)
                    ProcessamentoEstado.ERRO -> abandonarLoteEmErro(lote)
                }
            } catch (e: RegistradoraException) {
                log.warn("consulta do lote {} falhou: {}", lote.loteId, e.message)
            }
        }
        abandonarPresos()
        return resolvidos
    }

    private fun abandonarPresos() {
        val presas = operacaoRepository.buscarEnviadosPresos(
            properties.reconciliacao.consultasLimite,
            properties.reconciliacao.lotesPorCiclo,
        )
        if (presas.isEmpty()) return
        val erro = "desfecho indeterminado na registradora apos limite de consultas"
        presas.chunked(LOTE_ABANDONO).forEach { fatia ->
            transactionTemplate.executeWithoutResult {
                operacaoRepository.marcarIndeterminado(fatia.map { it.id }, erro)
                fatia.forEach { presa ->
                    dlqRepository.inserir(OrigemDlq.OUTBOX, presa.id, objectMapper.writeValueAsString(presa), erro)
                }
            }
        }
        log.warn(
            "{} operacoes marcadas INDETERMINADO — conferir na registradora antes de reenviar: {}",
            presas.size, presas.joinToString { "${it.referenciaExterna}@${it.loteId}" },
        )
    }

    private fun aplicarItens(lote: LoteEnviado, itens: List<io.dupesc.domain.model.ItemResultado>): Int {
        var resolvidos = 0
        itens.forEach { item ->
            val operacao = operacaoRepository.buscarPorReferencia(item.referenciaExterna) ?: return@forEach
            when (item.estado) {
                ProcessamentoEstado.PROCESSADO -> {
                    val iud = item.operationId ?: return@forEach
                    val aplicado = runCatching {
                        transactionTemplate.execute {
                            if (operacaoRepository.marcarRegistrado(operacao.id, iud)) {
                                registrarTitulo(operacao, iud, lote.registradora)
                                true
                            } else {
                                false
                            }
                        } == true
                    }.getOrElse {
                        log.error("item {} do lote {} nao aplicado", item.referenciaExterna, lote.loteId, it)
                        false
                    }
                    if (aplicado) resolvidos++
                }
                ProcessamentoEstado.REJEITADO -> {
                    runCatching {
                        transactionTemplate.executeWithoutResult {
                            operacaoRepository.marcarRecusado(operacao.id, objectMapper.writeValueAsString(item.erros))
                        }
                        resolvidos++
                    }.onFailure {
                        log.error("item {} do lote {} nao recusado", item.referenciaExterna, lote.loteId, it)
                    }
                }
                else -> Unit
            }
        }
        log.info("lote {} reconciliado: {} itens resolvidos", lote.loteId, resolvidos)
        return resolvidos
    }

    private fun registrarTitulo(operacao: OperacaoLinha, iud: String, registradora: String) {
        if (tituloRepository.inserirSeNovo(iud, operacao.duplicataId, operacao.id, registradora) ==
            ResultadoTitulo.CONFLITO_ATIVO
        ) {
            val erro = "duplicata ${operacao.duplicataId} ja tem titulo ativo — registro duplicado em $registradora " +
                "(iud $iud, referencia ${operacao.referenciaExterna})"
            log.error(erro)
            dlqRepository.inserir(OrigemDlq.OUTBOX, operacao.id, "{}", erro)
        }
    }

    private fun abandonarLoteEmErro(lote: LoteEnviado) {
        val erro = "lote ${lote.loteId} em ERRO na registradora — desfecho por item desconhecido"
        transactionTemplate.executeWithoutResult {
            operacaoRepository.marcarIndeterminado(lote.operacaoIds, erro)
            lote.operacaoIds.forEach { id -> dlqRepository.inserir(OrigemDlq.OUTBOX, id, "{}", erro) }
        }
        log.error(
            "lote {} em ERRO: {} operacoes marcadas INDETERMINADO — conferir na registradora antes de reenviar",
            lote.loteId, lote.operacaoIds.size,
        )
    }

    companion object {
        const val LOCK_RECONCILIADOR = 741002L
        private const val LOTE_ABANDONO = 100
    }
}
