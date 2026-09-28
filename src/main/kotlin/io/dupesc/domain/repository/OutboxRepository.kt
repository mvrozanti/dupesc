package io.dupesc.domain.repository

import io.dupesc.domain.model.OperacaoLegado
import java.time.Instant

data class Claim(val operacaoId: Long, val attemptCount: Int, val claimedUntil: Instant)

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
    fun falhaRetryavel(operacaoIds: List<Long>, atrasoMs: Long, erro: String)
    fun marcarDlq(operacaoIds: List<Long>)
    fun repararLeasesVencidos(): List<Long>
    fun promoverEnviadosComLote(): List<Long>
    fun idadePendenteMaisAntigoMs(): Long?
    fun contarPendentes(): Long
    fun reprocessar(operacaoId: Long): Boolean
}
