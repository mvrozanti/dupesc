package io.dupesc.domain.repository

interface TituloRepository {
    fun inserirSeNovo(iud: String, duplicataId: Long, operacaoId: Long)
}
