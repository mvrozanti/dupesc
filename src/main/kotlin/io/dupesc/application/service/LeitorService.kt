package io.dupesc.application.service

import io.dupesc.domain.port.LegacyPort
import io.dupesc.domain.repository.CheckpointRepository
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OrigemDlq
import io.dupesc.infrastructure.configuration.DupeProperties
import io.dupesc.infrastructure.db.AdvisoryLockManager
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class LeitorService(
    private val legadoPort: LegacyPort,
    private val ingestaoService: IngestaoService,
    private val checkpointRepository: CheckpointRepository,
    private val operacaoRepository: OperacaoRepository,
    private val advisoryLockManager: AdvisoryLockManager,
    private val properties: DupeProperties,
    private val transactionTemplate: TransactionTemplate,
) {

    fun drenar(): Int? = advisoryLockManager.comLock(LOCK_LEITOR) { drenarComLock() }

    private fun drenarComLock(): Int {
        var lidas = 0
        var paginaAtual = checkpointRepository.buscar(CHECKPOINT_PAGINA) ?: 0L
        repeat(properties.leitor.maxPaginasPorCiclo) {
            if (operacaoRepository.contarPendentes() >= properties.leitor.maxQueuePendente) return lidas
            val pagina = legadoPort.buscarPagina(paginaAtual + 1, properties.leitor.tamanhoPagina)
            if (pagina.operacoes.isEmpty()) return lidas
            lidas += ingestaoService.gravar(pagina.operacoes, OrigemDlq.LEITOR)
            val avancou = transactionTemplate.execute {
                checkpointRepository.avancar(CHECKPOINT_PAGINA, paginaAtual, pagina.pagina)
            } ?: false
            if (!avancou) return lidas
            paginaAtual = pagina.pagina
            if (!pagina.temMais) return lidas
        }
        return lidas
    }

    companion object {
        const val CHECKPOINT_PAGINA = "legado.pagina"
        const val LOCK_LEITOR = 741001L
    }
}
