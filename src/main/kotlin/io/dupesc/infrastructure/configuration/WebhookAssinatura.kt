package io.dupesc.infrastructure.configuration

import org.springframework.stereotype.Component
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class WebhookAssinatura(properties: DupeProperties) {

    private val secrets = mapOf("CERC" to properties.cerc.webhookSecret)

    fun valida(registradora: String, corpo: String, assinatura: String?): Boolean {
        val secret = secrets[registradora.uppercase()] ?: return false
        if (assinatura.isNullOrBlank()) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val esperada = mac.doFinal(corpo.toByteArray()).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(esperada.toByteArray(), assinatura.lowercase().toByteArray())
    }
}
