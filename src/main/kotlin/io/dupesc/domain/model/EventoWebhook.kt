package io.dupesc.domain.model

data class EventoLoteFinalizado(
    val eventId: String,
    val loteId: String,
    val statusLote: ProcessamentoEstado,
    val itensProcessados: List<ItemProcessado> = emptyList(),
    val itensInvalidos: List<ItemInvalido> = emptyList(),
)

data class ItemProcessado(val referenciaExterna: String, val iud: String)

data class ItemInvalido(val referenciaExterna: String?, val erros: List<ErroRegistradora>)
