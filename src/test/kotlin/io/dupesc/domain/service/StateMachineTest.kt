package io.dupesc.domain.service

import io.dupesc.domain.model.EstadoOperacao
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateMachineTest {

    @Test
    fun `todas as transicoes legais sao aceitas`() {
        assertThat(StateMachine.transicionar(EstadoOperacao.PENDENTE, EventoOperacao.Reivindicada))
            .isEqualTo(EstadoOperacao.EM_ENVIO)
        assertThat(StateMachine.transicionar(EstadoOperacao.EM_ENVIO, EventoOperacao.Enviada("lote-1")))
            .isEqualTo(EstadoOperacao.ENVIADO)
        assertThat(StateMachine.transicionar(EstadoOperacao.EM_ENVIO, EventoOperacao.FalhaRetryavel("timeout")))
            .isEqualTo(EstadoOperacao.PENDENTE)
        assertThat(StateMachine.transicionar(EstadoOperacao.EM_ENVIO, EventoOperacao.FalhaPermanente("4xx")))
            .isEqualTo(EstadoOperacao.FALHA_PERMANENTE)
        assertThat(StateMachine.transicionar(EstadoOperacao.EM_ENVIO, EventoOperacao.LeaseExpirada))
            .isEqualTo(EstadoOperacao.PENDENTE)
        assertThat(StateMachine.transicionar(EstadoOperacao.ENVIADO, EventoOperacao.Processada("IUD")))
            .isEqualTo(EstadoOperacao.REGISTRADO)
        assertThat(StateMachine.transicionar(EstadoOperacao.ENVIADO, EventoOperacao.Recusada(emptyList())))
            .isEqualTo(EstadoOperacao.RECUSADO)
        assertThat(StateMachine.transicionar(EstadoOperacao.ENVIADO, EventoOperacao.FalhaPermanente("erro")))
            .isEqualTo(EstadoOperacao.FALHA_PERMANENTE)
        assertThat(StateMachine.transicionar(EstadoOperacao.PENDENTE, EventoOperacao.Processada("IUD")))
            .isEqualTo(EstadoOperacao.REGISTRADO)
        assertThat(StateMachine.transicionar(EstadoOperacao.EM_ENVIO, EventoOperacao.Processada("IUD")))
            .isEqualTo(EstadoOperacao.REGISTRADO)
    }

    @Test
    fun `transicoes ilegais retornam nulo`() {
        assertThat(StateMachine.transicionar(EstadoOperacao.EM_ENVIO, EventoOperacao.Reivindicada)).isNull()
        assertThat(StateMachine.transicionar(EstadoOperacao.PENDENTE, EventoOperacao.Enviada("x"))).isNull()
        assertThat(StateMachine.transicionar(EstadoOperacao.REGISTRADO, EventoOperacao.Processada("IUD"))).isNull()
        assertThat(StateMachine.transicionar(EstadoOperacao.RECUSADO, EventoOperacao.Processada("IUD"))).isNull()
        assertThat(StateMachine.transicionar(EstadoOperacao.PENDENTE, EventoOperacao.Recusada(emptyList()))).isNull()
        assertThat(StateMachine.transicionar(EstadoOperacao.PENDENTE, EventoOperacao.LeaseExpirada)).isNull()
        assertThat(StateMachine.transicionar(EstadoOperacao.ENVIADO, EventoOperacao.LeaseExpirada)).isNull()
    }
}
