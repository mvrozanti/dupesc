package io.dupesc.domain.repository

enum class OrigemDlq { OUTBOX, WEBHOOK, LEITOR, INGESTAO }

interface DlqRepository {
    fun inserir(origem: OrigemDlq, referencia: Long?, payloadJson: String, erro: String)
    fun contarAbertos(): Long
}
