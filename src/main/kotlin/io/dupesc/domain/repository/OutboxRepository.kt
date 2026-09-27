package io.dupesc.domain.repository

import io.dupesc.domain.model.OperacaoLegado

data class Claim(val operacaoId: Long, val attemptCount: Int)

data class ItemComando(
    val operacaoId: Long,
    val referenciaExterna: String,
    val duplicataId: Long,
    val registradora: String,
    val operacaoLegado: OperacaoLegado,
)

interface OutboxRepository {
    fun inserir(operacaoId: Long)
    fun reivindicar(tamanho: Int, podId: String, leaseMs: Long): List<Claim>
    fun buscarComandos(operacaoIds: List<Long>): List<ItemComando>
    fun marcarProcessado(operacaoIds: List<Long>)
    fun falhaRetryavel(operacaoIds: List<Long>, atrasoMs: Long, erro: String, zeraTentativas: Boolean = false)
    fun marcarDlq(operacaoIds: List<Long>)
    fun reabrir(operacaoIds: List<Long>)
    fun repararLeasesVencidos(): List<Long>
    fun promoverEnviadosComLote(): List<Long>
    fun idadePendenteMaisAntigoMs(): Long?
}
