package io.dupesc.domain.service

import java.security.MessageDigest

object ReferenciaExterna {

    private const val LIMITE = 100

    fun de(operacaoLegadoId: String): String {
        val candidata = "DU-$operacaoLegadoId"
        return if (candidata.length <= LIMITE) {
            candidata
        } else {
            "DU-" + sha256Hex(operacaoLegadoId).take(LIMITE - 3)
        }
    }

    private fun sha256Hex(valor: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(valor.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
