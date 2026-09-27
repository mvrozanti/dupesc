package io.dupesc.domain.repository

import io.dupesc.domain.model.EstadoOperacao
import java.time.Instant

data class LoteEnviado(val registradora: String, val loteId: String, val operacaoIds: List<Long>)

data class OperacaoLinha(
    val id: Long,
    val referenciaExterna: String,
    val estado: EstadoOperacao,
    val intencaoId: Long,
    val duplicataId: Long,
)

interface OperacaoRepository {
    fun inserir(intencaoId: Long, referenciaExterna: String, registradora: String): Long
    fun registrarLote(ids: List<Long>, loteId: String)
    fun marcarEnviado(ids: List<Long>, loteId: String)
    fun falhaRetryavel(ids: List<Long>, erro: String, zeraTentativas: Boolean = false)
    fun falhaPermanente(ids: List<Long>, erro: String)
    fun resetarParaPendente(ids: List<Long>)
    fun marcarRegistrado(id: Long, iud: String): Boolean
    fun marcarRecusado(id: Long, errosJson: String): Boolean
    fun buscarPorReferencia(referenciaExterna: String): OperacaoLinha?
    fun buscarLotesEnviados(idadeMin: Instant): List<LoteEnviado>
    fun contarPendentes(): Long
}
