package io.dupesc.infrastructure.configuration

import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.OutboxRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class DupeMetrics(
    registry: MeterRegistry,
    private val outboxRepository: OutboxRepository,
    private val dlqRepository: DlqRepository,
) {
    private val webhook401 = registry.counter("dupe.webhook.401")

    init {
        Gauge.builder("dupe.fila.pendente", outboxRepository) { it.contarPendentes().toDouble() }
            .register(registry)
        Gauge.builder("dupe.fila.idade_ms", outboxRepository) { it.idadePendenteMaisAntigoMs()?.toDouble() ?: 0.0 }
            .register(registry)
        Gauge.builder("dupe.dlq.abertos", dlqRepository) { it.contarAbertos().toDouble() }
            .register(registry)
    }

    fun registrarWebhook401() = webhook401.increment()
}
