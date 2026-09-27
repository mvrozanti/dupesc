package io.dupesc.application.service

import io.dupesc.domain.port.RegistradoraException
import io.dupesc.domain.port.RegistradoraPort
import org.springframework.stereotype.Component

@Component
class RegistradoraRegistry(private val cerc: ResilientRegistradora) {

    fun port(registradora: String): RegistradoraPort =
        when (registradora.uppercase()) {
            "CERC" -> cerc
            else -> throw RegistradoraException("registradora desconhecida: $registradora", retryavel = false)
        }
}
