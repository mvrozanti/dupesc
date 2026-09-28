package io.dupesc.infrastructure.cerc

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.domain.port.RegistradoraException
import io.dupesc.infrastructure.configuration.DupeProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.Base64

@Component
class CercClient(
    properties: DupeProperties,
    builder: RestClient.Builder,
    private val objectMapper: ObjectMapper,
) {
    private val client: RestClient = builder.baseUrl(properties.cerc.baseUrl).build()
    private val tokenClient: RestClient = RestClient.create()
    private val tokenUrl = properties.cerc.tokenUrl
    private val basicAuth = Base64.getEncoder()
        .encodeToString("${properties.cerc.clientId}:${properties.cerc.clientSecret}".toByteArray())

    @Volatile
    private var tokenCache: TokenOauth? = null
    private var tokenObtidoEm: Instant = Instant.EPOCH
    private val tokenLock = Any()

    fun enviarLote(itens: List<Map<String, Any?>>): RetornoLote {
        val token = token()
        return client.post()
            .uri("/v2/lote/escrituracao/duplicata/fatura")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(itens)
            .exchange { _, res ->
                when {
                    res.statusCode.is2xxSuccessful -> objectMapper.readValue(res.body, RetornoLote::class.java)
                    else -> throw falhaDe(res)
                }
            }!!
    }

    fun consultarStatus(loteId: String): StatusLote {
        val token = token()
        return client.get()
            .uri("/v2/lote/{id}/status", loteId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .exchange { _, res ->
                when {
                    res.statusCode.is2xxSuccessful -> objectMapper.readValue(res.body, StatusLote::class.java)
                    else -> throw falhaDe(res)
                }
            }!!
    }

    private fun token(): String = synchronized(tokenLock) {
        val atual = tokenCache
        if (atual != null && tokenObtidoEm.plusSeconds(atual.expires_in - 60).isAfter(Instant.now())) {
            return@synchronized atual.access_token
        }
        val novo = tokenClient.post()
            .uri(tokenUrl)
            .header(HttpHeaders.AUTHORIZATION, "Basic $basicAuth")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials")
            .exchange { _, res ->
                when {
                    res.statusCode.is2xxSuccessful -> objectMapper.readValue(res.body, TokenOauth::class.java)
                    else -> throw RegistradoraException("oauth da CERC falhou (HTTP ${res.statusCode.value()})", retryavel = true)
                }
            }!!
        tokenObtidoEm = Instant.now()
        tokenCache = novo
        novo.access_token
    }

    private fun falhaDe(res: ClientHttpResponse): RegistradoraException =
        when (res.statusCode.value()) {
            401 -> {
                tokenCache = null
                RegistradoraException("credencial invalida (401)", retryavel = true, naoEsgota = true)
            }
            in ROTA_OU_ACESSO -> RegistradoraException(
                "rota ou acesso da CERC rejeitado (HTTP ${res.statusCode.value()}) — conferir configuracao",
                retryavel = true,
                naoEsgota = true,
            )
            423 -> RegistradoraException("fora da janela operacional da CERC", retryavel = true, naoEsgota = true)
            429 -> RegistradoraException("limite de taxa da CERC", retryavel = true, naoEsgota = true)
            in 400..499 -> RegistradoraException(
                "rejeitado pela CERC (HTTP ${res.statusCode.value()})",
                retryavel = false,
                rejeicaoDeConteudo = true,
            )
            else -> RegistradoraException("erro tecnico da CERC (HTTP ${res.statusCode.value()})", retryavel = true)
        }

    companion object {
        private val ROTA_OU_ACESSO = setOf(403, 404, 405, 408, 415)
    }
}
