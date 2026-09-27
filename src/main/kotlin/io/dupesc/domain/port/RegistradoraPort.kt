package io.dupesc.domain.port

import io.dupesc.domain.model.ConsultaResultado
import io.dupesc.domain.model.EnvioHandle
import io.dupesc.domain.model.RegistroComando

interface RegistradoraPort {
    fun enviar(comandos: List<RegistroComando>): EnvioHandle
    fun consultar(handle: EnvioHandle): ConsultaResultado
}

class RegistradoraException(message: String, val retryavel: Boolean) : RuntimeException(message)
