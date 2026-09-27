package io.dupesc.domain.model

import java.math.BigDecimal
import java.time.LocalDate

data class OperacaoLegado(
    val id: String,
    val duplicataId: Long?,
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

data class InformacoesPagamentoLegado(
    val tipoInstrumento: String?,
    val iban: String?,
    val chavePix: String?,
)

data class PaginaLegado(
    val pagina: Long,
    val temMais: Boolean,
    val operacoes: List<OperacaoLegado>,
)
