package io.dupesc.domain.repository

interface EventoRepository {
    fun registrarSeNovo(registradora: String, eventId: String, tipo: String, payloadJson: String): Boolean
}
