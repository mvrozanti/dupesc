package io.dupesc.domain.repository

enum class OrigemDlq { OUTBOX, WEBHOOK, LEITOR, INGESTAO }

data class DlqLinha(
    val id: Long,
    val origem: String,
    val referencia: Long?,
    val payloadJson: String,
    val erro: String,
)

interface DlqRepository {
    fun inserir(origem: OrigemDlq, referencia: Long?, payloadJson: String, erro: String)
    fun contarAbertos(): Long
    fun listarAbertos(limite: Int): List<DlqLinha>
    fun buscar(id: Long): DlqLinha?
    fun marcarStatus(id: Long, status: String)
}
