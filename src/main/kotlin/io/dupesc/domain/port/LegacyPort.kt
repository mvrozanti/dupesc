package io.dupesc.domain.port

import io.dupesc.domain.model.PaginaLegado

interface LegacyPort {
    fun buscarPagina(pagina: Long, tamanho: Int): PaginaLegado
}
