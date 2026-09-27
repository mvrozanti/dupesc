package io.dupesc.application.job

import io.dupesc.application.service.ReconciliacaoService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ReconciliadorJob(private val reconciliacaoService: ReconciliacaoService) {

    private val log = LoggerFactory.getLogger(ReconciliadorJob::class.java)

    @Scheduled(fixedDelayString = "\${dupe.reconciliacao.intervalo-ms:300000}")
    fun executar() {
        runCatching { reconciliacaoService.executar() }
            .onFailure { log.warn("reconciliador: {}", it.message) }
    }
}
