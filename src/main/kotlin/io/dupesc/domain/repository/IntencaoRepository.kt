package io.dupesc.domain.repository

interface IntencaoRepository {
    fun inserirSeNovo(operacaoLegadoId: String, duplicataId: Long, dadosJson: String): Long?
}
