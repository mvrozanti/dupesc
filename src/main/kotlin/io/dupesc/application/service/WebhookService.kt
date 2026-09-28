package io.dupesc.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.domain.model.EventoLoteFinalizado
import io.dupesc.domain.model.ProcessamentoEstado
import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.EventoRepository
import io.dupesc.domain.repository.OperacaoLinha
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OrigemDlq
import io.dupesc.domain.repository.ResultadoTitulo
import io.dupesc.domain.repository.TituloRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class WebhookService(
    private val eventoRepository: EventoRepository,
    private val operacaoRepository: OperacaoRepository,
    private val tituloRepository: TituloRepository,
    private val dlqRepository: DlqRepository,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(WebhookService::class.java)

    fun processar(registradora: String, corpo: String) {
        try {
            val evento = objectMapper.readValue(corpo, EventoLoteFinalizado::class.java)
            val novo = transactionTemplate.execute { aplicar(registradora, evento, corpo) } ?: false
            if (!novo) log.info("webhook {} de {} ja visto, ignorado", evento.eventId, registradora)
        } catch (e: TransientDataAccessException) {
            log.warn("falha transitoria de banco no webhook de {} — devolvendo 500 para retry", registradora)
            throw e
        } catch (e: Exception) {
            try {
                dlqRepository.inserir(OrigemDlq.WEBHOOK, null, corpo, "webhook nao processado: ${e.message}")
                log.warn("webhook de {} para DLQ: {}", registradora, e.message)
            } catch (e2: Exception) {
                log.error("webhook de {} nem a DLQ gravou: {}", registradora, e2.message)
                throw e2
            }
        }
    }

    private fun aplicar(registradora: String, evento: EventoLoteFinalizado, corpo: String): Boolean {
        val novo = eventoRepository.registrarSeNovo(registradora, evento.eventId, evento.tipo, corpo)
        if (!novo) return false
        when (evento.statusLote) {
            ProcessamentoEstado.PROCESSADO -> aplicarProcessados(registradora, evento)
            ProcessamentoEstado.REJEITADO -> aplicarInvalidos(evento)
            else -> Unit
        }
        return true
    }

    private fun aplicarProcessados(registradora: String, evento: EventoLoteFinalizado) {
        evento.itensProcessados.forEach { item ->
            val operacao = operacaoRepository.buscarPorReferencia(item.referenciaExterna) ?: return@forEach
            if (!loteConfere(operacao, evento.loteId)) return@forEach
            if (operacaoRepository.marcarRegistrado(operacao.id, item.iud)) {
                registrarTitulo(operacao, item.iud, registradora)
            }
        }
    }

    private fun aplicarInvalidos(evento: EventoLoteFinalizado) {
        evento.itensInvalidos.forEach { item ->
            val referencia = item.referenciaExterna ?: return@forEach
            val operacao = operacaoRepository.buscarPorReferencia(referencia) ?: return@forEach
            if (!loteConfere(operacao, evento.loteId)) return@forEach
            operacaoRepository.marcarRecusado(operacao.id, objectMapper.writeValueAsString(item.erros))
        }
    }

    private fun registrarTitulo(operacao: OperacaoLinha, iud: String, registradora: String) {
        if (tituloRepository.inserirSeNovo(iud, operacao.duplicataId, operacao.id, registradora) ==
            ResultadoTitulo.CONFLITO_ATIVO
        ) {
            val erro = "duplicata ${operacao.duplicataId} ja tem titulo ativo — registro duplicado em $registradora " +
                "(iud $iud, referencia ${operacao.referenciaExterna})"
            log.error(erro)
            dlqRepository.inserir(OrigemDlq.WEBHOOK, operacao.id, "{}", erro)
        }
    }

    private fun loteConfere(operacao: OperacaoLinha, loteId: String): Boolean {
        if (operacao.loteId == loteId) return true
        val erro = "lote_id $loteId nao confere com ${operacao.loteId} para ${operacao.referenciaExterna}"
        log.warn("item do webhook ignorado: {}", erro)
        dlqRepository.inserir(OrigemDlq.WEBHOOK, operacao.id, "{}", erro)
        return false
    }
}
