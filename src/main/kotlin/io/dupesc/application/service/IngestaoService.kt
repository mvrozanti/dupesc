package io.dupesc.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.domain.model.OperacaoLegado
import io.dupesc.domain.model.Registradoras
import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.IntencaoRepository
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OrigemDlq
import io.dupesc.domain.repository.OutboxRepository
import io.dupesc.domain.service.ReferenciaExterna
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class IngestaoService(
    private val intencaoRepository: IntencaoRepository,
    private val operacaoRepository: OperacaoRepository,
    private val outboxRepository: OutboxRepository,
    private val dlqRepository: DlqRepository,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(IngestaoService::class.java)

    fun gravar(operacoes: List<OperacaoLegado>, origem: OrigemDlq): Int =
        transactionTemplate.execute {
            var novas = 0
            operacoes.forEach { operacao ->
                try {
                    if (gravarOperacao(operacao)) novas++
                } catch (e: Exception) {
                    dlqRepository.inserir(
                        origem,
                        null,
                        objectMapper.writeValueAsString(operacao),
                        "operacao de entrada invalida: ${e.message}",
                    )
                    log.warn("operacao de entrada {} para DLQ: {}", operacao.id, e.message)
                }
            }
            novas
        }!!

    private fun gravarOperacao(operacao: OperacaoLegado): Boolean {
        val duplicataId = operacao.duplicataId
            ?: throw IllegalArgumentException("duplicataId ausente")
        val intencaoId = intencaoRepository.inserirSeNovo(
            operacaoLegadoId = operacao.id,
            duplicataId = duplicataId,
            dadosJson = objectMapper.writeValueAsString(operacao),
        ) ?: return false
        val operacaoId = operacaoRepository.inserir(intencaoId, ReferenciaExterna.de(operacao.id), Registradoras.CERC)
        outboxRepository.inserir(operacaoId)
        return true
    }
}
