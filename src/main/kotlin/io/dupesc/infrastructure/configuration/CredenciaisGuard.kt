package io.dupesc.infrastructure.configuration

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class CredenciaisGuard(properties: DupeProperties, environment: Environment) {

    init {
        val perfis = environment.activeProfiles.toSet()
        val permitidos = setOf("test", "demo", "mock-cerc", "mock-legado")
        if (perfis.none { it in permitidos } && !properties.credenciaisImpostas) {
            val suspeitas = listOf(
                "dupe.cerc.client-secret" to properties.cerc.clientSecret,
                "dupe.cerc.webhook-secret" to properties.cerc.webhookSecret,
                "dupe.legado.api-key" to properties.legado.apiKey,
                "dupe.admin.api-key" to properties.admin.apiKey,
                "spring.datasource.password" to environment.getProperty("spring.datasource.password").orEmpty(),
            ).filter { (_, valor) -> fraca(valor) }

            if (suspeitas.isNotEmpty()) {
                throw IllegalStateException(
                    "credenciais placeholder em perfil nao-dev: ${suspeitas.joinToString { it.first }}",
                )
            }
            if (properties.podId.isBlank() || properties.podId == "local") {
                throw IllegalStateException("DUPE_POD_ID nao definido em perfil nao-dev")
            }
        }
    }

    private fun fraca(valor: String): Boolean =
        valor.isBlank() || valor.length < TAMANHO_MINIMO || PLACEHOLDERS.any { valor.lowercase().startsWith(it) }

    companion object {
        private const val TAMANHO_MINIMO = 16
        private val PLACEHOLDERS = listOf("poc-", "dupesc", "changeme", "secret", "password", "test", "admin")
    }
}
