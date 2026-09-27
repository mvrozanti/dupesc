package io.dupesc.application.job

import io.dupesc.application.service.LeitorService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class LeitorJob(private val leitorService: LeitorService) {

    private val log = LoggerFactory.getLogger(LeitorJob::class.java)

    @Scheduled(fixedDelayString = "\${dupe.leitor.intervalo-ms:5000}")
    fun executar() {
        runCatching { leitorService.drenar() }
            .onFailure { log.warn("leitor: {}", it.message) }
    }
}
