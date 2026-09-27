package io.dupesc.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.domain.model.OperacaoLegado
import io.dupesc.domain.model.PaginaLegado
import io.dupesc.domain.port.LegacyPort
import io.dupesc.domain.repository.CheckpointRepository
import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.IntencaoRepository
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OrigemDlq
import io.dupesc.domain.repository.OutboxRepository
import io.dupesc.domain.service.ReferenciaExterna
import io.dupesc.infrastructure.configuration.DupeProperties
import io.dupesc.infrastructure.db.AdvisoryLockManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LeitorService(
    private val legadoPort: LegacyPort,
    private val intencaoRepository: IntencaoRepository,
    private val operacaoRepository: OperacaoRepository,
    private val outboxRepository: OutboxRepository,
    private val checkpointRepository: CheckpointRepository,
    private val dlqRepository: DlqRepository,
    private val advisoryLockManager: AdvisoryLockManager,
    private val properties: DupeProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(LeitorService::class.java)

    fun drenar(): Int? = advisoryLockManager.comLock(LOCK_LEITOR) { drenarComLock() }

    private fun drenarComLock(): Int {
        var lidas = 0
        var paginaAtual = checkpointRepository.buscar(CHECKPOINT_PAGINA) ?: 0L
        repeat(properties.leitor.maxPaginasPorCiclo) {
            if (operacaoRepository.contarPendentes() >= properties.leitor.maxQueuePendente) return lidas
            val pagina = legadoPort.buscarPagina(paginaAtual + 1, properties.leitor.tamanhoPagina)
            gravarPagina(pagina)
            lidas += pagina.operacoes.size
            if (!checkpointRepository.avancar(CHECKPOINT_PAGINA, paginaAtual, pagina.pagina)) return lidas
            paginaAtual = pagina.pagina
            if (!pagina.temMais) return lidas
        }
        return lidas
    }

    private fun gravarPagina(pagina: PaginaLegado) {
        pagina.operacoes.forEach { operacao ->
            try {
                gravarOperacao(operacao)
            } catch (e: Exception) {
                dlqRepository.inserir(
                    OrigemDlq.LEITOR,
                    null,
                    objectMapper.writeValueAsString(operacao),
                    "operacao legado invalida: ${e.message}",
                )
                log.warn("operacao legado {} para DLQ: {}", operacao.id, e.message)
            }
        }
    }

    private fun gravarOperacao(operacao: OperacaoLegado) {
        val duplicataId = operacao.duplicataId
            ?: throw IllegalArgumentException("duplicataId ausente")
        val intencaoId = intencaoRepository.inserirSeNovo(
            operacaoLegadoId = operacao.id,
            duplicataId = duplicataId,
            dadosJson = objectMapper.writeValueAsString(operacao),
        ) ?: return
        val operacaoId = operacaoRepository.inserir(intencaoId, ReferenciaExterna.de(operacao.id))
        outboxRepository.inserir(operacaoId)
    }

    companion object {
        const val CHECKPOINT_PAGINA = "legado.pagina"
        const val LOCK_LEITOR = 741001L
    }
}
