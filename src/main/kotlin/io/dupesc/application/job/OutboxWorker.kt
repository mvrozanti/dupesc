package io.dupesc.application.job

import io.dupesc.application.service.RegistroService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxWorker(private val registroService: RegistroService) {

    @Scheduled(fixedDelayString = "\${dupe.worker.intervalo-ms:1000}")
    fun executar() = registroService.processarLote()
}
