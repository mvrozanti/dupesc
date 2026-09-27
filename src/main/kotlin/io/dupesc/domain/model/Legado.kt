package io.dupesc.domain.model

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.LocalDate

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OperacaoLegado(
    val id: String,
    val duplicataId: Long?,
    val tipo: String?,
    val numeroFatura: String?,
    val parcela: Int?,
    val assinatura: String?,
    val emissao: LocalDate?,
    val vencimento: LocalDate?,
    val valor: BigDecimal?,
    val sacadorDocumento: String?,
    val sacadorNome: String?,
    val sacadoDocumento: String?,
    val sacadoNome: String?,
    val sacadoEmail: String?,
    val informacoesPagamento: InformacoesPagamentoLegado?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class InformacoesPagamentoLegado(
    val tipoInstrumento: String?,
    val iban: String?,
    val chavePix: String?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PaginaLegado(
    val operacoes: List<OperacaoLegado>,
    val proximoCursor: Long?,
    val temMais: Boolean,
)
