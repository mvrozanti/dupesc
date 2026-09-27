package io.dupesc.application.job

import io.dupesc.application.service.RegistroService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxWorker(private val registroService: RegistroService) {

    private val log = LoggerFactory.getLogger(OutboxWorker::class.java)

    @Scheduled(fixedDelayString = "\${dupe.worker.intervalo-ms:1000}")
    fun executar() {
        runCatching { registroService.processarLote() }
            .onFailure { log.warn("worker: {}", it.message) }
    }
}
