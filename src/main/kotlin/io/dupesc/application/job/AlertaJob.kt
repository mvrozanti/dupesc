package io.dupesc.application.job

import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OutboxRepository
import io.dupesc.infrastructure.configuration.DupeProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AlertaJob(
    private val operacaoRepository: OperacaoRepository,
    private val outboxRepository: OutboxRepository,
    private val dlqRepository: DlqRepository,
    private val properties: DupeProperties,
) {

    private val log = LoggerFactory.getLogger(AlertaJob::class.java)

    @Scheduled(fixedDelayString = "\${dupe.alerta.intervalo-ms:60000}")
    fun executar() {
        val pendentes = operacaoRepository.contarPendentes()
        val maisAntigoMs = outboxRepository.idadePendenteMaisAntigoMs()
        val dlq = dlqRepository.contarAbertos()
        if (pendentes > properties.alerta.queueLimite) {
            log.warn("fila com {} itens pendentes (limite {})", pendentes, properties.alerta.queueLimite)
        }
        if (maisAntigoMs != null && maisAntigoMs > 15 * 60_000) {
            log.warn("item pendente ha {} minutos", maisAntigoMs / 60_000)
        }
        if (dlq > 0) {
            log.warn("{} itens na DLQ aguardando revisao", dlq)
        }
    }
}
