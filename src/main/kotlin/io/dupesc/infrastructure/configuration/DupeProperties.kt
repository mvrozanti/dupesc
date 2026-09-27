package io.dupesc.infrastructure.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dupe")
data class DupeProperties(
    val podId: String,
    val worker: Worker = Worker(),
    val retry: Retry = Retry(),
    val reconciliacao: Reconciliacao = Reconciliacao(),
    val leitor: Leitor = Leitor(),
    val alerta: Alerta = Alerta(),
    val cerc: Cerc = Cerc(),
    val legado: Legado = Legado(),
    val admin: Admin = Admin(),
    val mock: Mock = Mock(),
) {
    data class Mock(val webhookDestino: List<String> = emptyList())
    data class Cerc(
        val baseUrl: String = "http://localhost:8081",
        val tokenUrl: String = "http://localhost:8081/oauth/token",
        val clientId: String = "poc-client",
        val clientSecret: String = "poc-secret",
        val webhookSecret: String = "poc-secret",
        val rateLimitRps: Int = 80,
    )
    data class Legado(val baseUrl: String = "http://localhost:8082", val apiKey: String = "poc-legado")
    data class Admin(val apiKey: String = "poc-admin")
    data class Worker(val batch: Int = 200, val intervaloMs: Long = 1000, val leaseMs: Long = 600_000)
    data class Retry(val baseMs: Long = 5_000, val capMs: Long = 600_000, val maxAttempts: Int = 5)
    data class Reconciliacao(
        val intervaloMs: Long = 300_000,
        val idadeMinMs: Long = 1_800_000,
        val consultasLimite: Int = 12,
    )
    data class Leitor(
        val intervaloMs: Long = 5_000,
        val tamanhoPagina: Int = 1000,
        val maxPaginasPorCiclo: Int = 10,
        val maxQueuePendente: Int = 50_000,
    )
    data class Alerta(val queueLimite: Int = 10_000)
}
