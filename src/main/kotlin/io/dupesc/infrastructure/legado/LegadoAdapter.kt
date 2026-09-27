package io.dupesc.infrastructure.legado

import io.dupesc.domain.model.PaginaLegado
import io.dupesc.domain.port.LegadoException
import io.dupesc.domain.port.LegacyPort
import io.dupesc.infrastructure.configuration.DupeProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

@Component
class LegadoAdapter(
    properties: DupeProperties,
    builder: RestClient.Builder,
) : LegacyPort {

    private val client: RestClient = builder.baseUrl(properties.legado.baseUrl).build()

    override fun buscarPagina(pagina: Long, tamanho: Int): PaginaLegado =
        try {
            client.get()
                .uri("/legado/operacoes?pagina={pagina}&tamanho={tamanho}", pagina, tamanho)
                .retrieve()
                .body(PaginaLegado::class.java)
                ?: throw LegadoException("resposta vazia do endpoint legado na pagina $pagina")
        } catch (e: RestClientException) {
            throw LegadoException("falha ao buscar pagina $pagina: ${e.message}", e)
        }
}
