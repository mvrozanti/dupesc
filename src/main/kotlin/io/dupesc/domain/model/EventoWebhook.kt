package io.dupesc.domain.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class EventoLoteFinalizado(
    val eventId: String,
    val tipo: String,
    val loteId: String,
    @JsonProperty("status")
    val statusLote: ProcessamentoEstado,
    val itensProcessados: List<ItemProcessado> = emptyList(),
    val itensInvalidos: List<ItemInvalido> = emptyList(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ItemProcessado(
    val referenciaExterna: String,
    @JsonProperty("identificador_item_processado")
    val iud: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ItemInvalido(val referenciaExterna: String?, val erros: List<ErroRegistradora>)
