package io.dupesc.infrastructure.cerc

import io.dupesc.domain.model.ConsultaResultado
import io.dupesc.domain.model.EnvioHandle
import io.dupesc.domain.model.RegistroComando
import io.dupesc.domain.port.RegistradoraException
import io.dupesc.domain.port.RegistradoraPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component("registradoraBruta")
@ConditionalOnProperty(name = ["dupe.cerc.enabled"], havingValue = "false")
class RegistradoraPortIndisponivel : RegistradoraPort {

    override fun enviar(comandos: List<RegistroComando>): EnvioHandle =
        throw RegistradoraException("integracao CERC desabilitada", retryavel = false)

    override fun consultar(handle: EnvioHandle): ConsultaResultado =
        throw RegistradoraException("integracao CERC desabilitada", retryavel = false)
}
