package io.dupesc.domain.repository

enum class ResultadoTitulo { NOVO, JA_EXISTE, CONFLITO_ATIVO }

interface TituloRepository {
    fun inserirSeNovo(iud: String, duplicataId: Long, operacaoId: Long, registradora: String): ResultadoTitulo
}
