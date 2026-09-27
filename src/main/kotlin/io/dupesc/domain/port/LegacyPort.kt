package io.dupesc.domain.port

import io.dupesc.domain.model.PaginaLegado

interface LegacyPort {
    fun buscarApos(cursor: Long, tamanho: Int): PaginaLegado
}

class LegadoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
