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

    override fun buscarApos(cursor: Long, tamanho: Int): PaginaLegado =
        try {
            client.get()
                .uri("/legado/operacoes?after_id={after}&tamanho={tamanho}", cursor, tamanho)
                .retrieve()
                .body(PaginaLegado::class.java)
                ?: throw LegadoException("resposta vazia do endpoint legado apos $cursor")
        } catch (e: RestClientException) {
            throw LegadoException("falha ao buscar apos $cursor: ${e.message}", e)
        }
}
