package io.dupesc.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.domain.model.EnvioHandle
import io.dupesc.domain.model.ProcessamentoEstado
import io.dupesc.domain.port.RegistradoraException
import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.LoteEnviado
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OrigemDlq
import io.dupesc.domain.repository.OutboxRepository
import io.dupesc.domain.repository.TituloRepository
import io.dupesc.infrastructure.configuration.DupeProperties
import io.dupesc.infrastructure.db.AdvisoryLockManager
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
        val lotes = operacaoRepository.buscarLotesEnviados(idadeMin)
        var resolvidos = 0
        lotes.forEach { lote ->
            transactionTemplate.executeWithoutResult {
                operacaoRepository.incrementarConsultas(lote.operacaoIds)
            }
            try {
                val resultado = registry.port(lote.registradora).consultar(EnvioHandle(lote.loteId))
                when (resultado.statusLote) {
                    ProcessamentoEstado.PROCESSANDO -> Unit
                    ProcessamentoEstado.PROCESSADO -> resolvidos += aplicarItens(lote, resultado.itens)
                    ProcessamentoEstado.REJEITADO -> resolvidos += aplicarItens(lote, resultado.itens)
                    ProcessamentoEstado.ERRO -> resetar(lote)
                }
            } catch (e: RegistradoraException) {
                log.warn("consulta do lote {} falhou: {}", lote.loteId, e.message)
            }
        }
        abandonarPresos()
        return resolvidos
    }

    private fun abandonarPresos() {
        val ids = operacaoRepository.buscarEnviadosPresos(properties.reconciliacao.consultasLimite)
        if (ids.isEmpty()) return
        transactionTemplate.executeWithoutResult {
            operacaoRepository.falhaPermanente(ids, "enviado preso alem do limite de consultas")
            ids.forEach { id ->
                dlqRepository.inserir(OrigemDlq.OUTBOX, id, "{}", "enviado preso alem do limite de consultas")
            }
        }
        log.warn("{} operacoes ENVIADO presas movidas para DLQ", ids.size)
    }

    private fun aplicarItens(lote: LoteEnviado, itens: List<io.dupesc.domain.model.ItemResultado>): Int {
        var resolvidos = 0
        itens.forEach { item ->
            val operacao = operacaoRepository.buscarPorReferencia(item.referenciaExterna) ?: return@forEach
            when (item.estado) {
                ProcessamentoEstado.PROCESSADO -> {
                    val iud = item.operationId ?: return@forEach
                    if (transactionTemplate.execute {
                            if (operacaoRepository.marcarRegistrado(operacao.id, iud)) {
                                tituloRepository.inserirSeNovo(iud, operacao.duplicataId, operacao.id)
                                true
                            } else {
                                false
                            }
                        } == true
                    ) {
                        resolvidos++
                    }
                }
                ProcessamentoEstado.REJEITADO -> {
                    transactionTemplate.executeWithoutResult {
                        operacaoRepository.marcarRecusado(operacao.id, objectMapper.writeValueAsString(item.erros))
                    }
                    resolvidos++
                }
                else -> Unit
            }
        }
        log.info("lote {} reconciliado: {} itens resolvidos", lote.loteId, resolvidos)
        return resolvidos
    }

    private fun resetar(lote: LoteEnviado) {
        transactionTemplate.executeWithoutResult {
            operacaoRepository.resetarParaPendente(lote.operacaoIds)
            outboxRepository.reabrir(lote.operacaoIds)
        }
        log.warn("lote {} em ERRO: {} operacoes reenfileiradas", lote.loteId, lote.operacaoIds.size)
    }

    companion object {
        const val LOCK_RECONCILIADOR = 741002L
    }
}
