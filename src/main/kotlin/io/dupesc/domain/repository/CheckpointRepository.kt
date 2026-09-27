package io.dupesc.domain.repository

interface CheckpointRepository {
    fun buscar(nome: String): Long?
    fun avancar(nome: String, atualEsperado: Long, novo: Long): Boolean
}
