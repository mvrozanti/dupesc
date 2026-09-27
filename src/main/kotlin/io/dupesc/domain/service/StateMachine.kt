package io.dupesc.domain.service

import io.dupesc.domain.model.ErroRegistradora
import io.dupesc.domain.model.EstadoOperacao

sealed interface EventoOperacao {
    data object Reivindicada : EventoOperacao
    data class Enviada(val loteId: String) : EventoOperacao
    data class Processada(val iud: String) : EventoOperacao
    data class Recusada(val erros: List<ErroRegistradora>) : EventoOperacao
    data class FalhaRetryavel(val erro: String) : EventoOperacao
    data class FalhaPermanente(val erro: String) : EventoOperacao
    data object LeaseExpirada : EventoOperacao
}

object StateMachine {

    fun transicionar(atual: EstadoOperacao, evento: EventoOperacao): EstadoOperacao? = when (evento) {
        EventoOperacao.Reivindicada -> when (atual) {
            EstadoOperacao.PENDENTE -> EstadoOperacao.EM_ENVIO
            else -> null
        }
        is EventoOperacao.Enviada -> when (atual) {
            EstadoOperacao.EM_ENVIO -> EstadoOperacao.ENVIADO
            else -> null
        }
        is EventoOperacao.Processada -> when (atual) {
            EstadoOperacao.PENDENTE, EstadoOperacao.EM_ENVIO, EstadoOperacao.ENVIADO -> EstadoOperacao.REGISTRADO
            else -> null
        }
        is EventoOperacao.Recusada -> when (atual) {
            EstadoOperacao.ENVIADO -> EstadoOperacao.RECUSADO
            else -> null
        }
        is EventoOperacao.FalhaRetryavel -> when (atual) {
            EstadoOperacao.EM_ENVIO -> EstadoOperacao.PENDENTE
            else -> null
        }
        is EventoOperacao.FalhaPermanente -> when (atual) {
            EstadoOperacao.PENDENTE, EstadoOperacao.EM_ENVIO, EstadoOperacao.ENVIADO -> EstadoOperacao.FALHA_PERMANENTE
            else -> null
        }
        EventoOperacao.LeaseExpirada -> when (atual) {
            EstadoOperacao.EM_ENVIO -> EstadoOperacao.PENDENTE
            else -> null
        }
    }

    fun origens(evento: EventoOperacao): Set<EstadoOperacao> = when (evento) {
        EventoOperacao.Reivindicada -> setOf(EstadoOperacao.PENDENTE)
        is EventoOperacao.Enviada -> setOf(EstadoOperacao.EM_ENVIO)
        is EventoOperacao.Processada -> setOf(EstadoOperacao.PENDENTE, EstadoOperacao.EM_ENVIO, EstadoOperacao.ENVIADO)
        is EventoOperacao.Recusada -> setOf(EstadoOperacao.ENVIADO)
        is EventoOperacao.FalhaRetryavel -> setOf(EstadoOperacao.EM_ENVIO)
        is EventoOperacao.FalhaPermanente -> setOf(EstadoOperacao.PENDENTE, EstadoOperacao.EM_ENVIO, EstadoOperacao.ENVIADO)
        EventoOperacao.LeaseExpirada -> setOf(EstadoOperacao.EM_ENVIO)
    }
}
