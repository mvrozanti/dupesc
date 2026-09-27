package io.dupesc.infrastructure.configuration

import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class ChaveApi {

    fun valida(recebida: String?, esperada: String): Boolean =
        recebida != null && MessageDigest.isEqual(recebida.toByteArray(), esperada.toByteArray())
}
