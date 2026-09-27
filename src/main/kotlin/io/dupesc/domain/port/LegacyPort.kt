package io.dupesc.domain.port

import io.dupesc.domain.model.PaginaLegado

interface LegacyPort {
    fun buscarPagina(pagina: Long, tamanho: Int): PaginaLegado
}

class LegadoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
