package io.dupesc.infrastructure.configuration

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class CredenciaisGuard(properties: DupeProperties, environment: Environment) {

    init {
        val perfis = environment.activeProfiles.toSet()
        val permitidos = setOf("test", "demo", "mock-cerc", "mock-legado")
        if (perfis.none { it in permitidos }) {
            val suspeitas = listOf(
                "dupe.cerc.client-secret" to properties.cerc.clientSecret,
                "dupe.cerc.webhook-secret" to properties.cerc.webhookSecret,
                "dupe.legado.api-key" to properties.legado.apiKey,
            ).filter { (_, valor) -> valor.startsWith("poc-") }

            if (suspeitas.isNotEmpty()) {
                throw IllegalStateException(
                    "credenciais placeholder em perfil nao-dev: ${suspeitas.joinToString { it.first }}",
                )
            }
        }
    }
}
