package io.dupesc.domain.model

import java.math.BigDecimal
import java.time.LocalDate

data class Parte(
    val documento: String,
    val nome: String?,
    val email: String? = null,
    val assinatura: String? = null,
)

data class InformacoesPagamento(
    val tipoInstrumento: String,
    val iban: String? = null,
    val chavePix: String? = null,
)

data class RegistroComando(
    val referenciaExterna: String,
    val duplicataId: Long,
    val emissao: LocalDate,
    val vencimento: LocalDate,
    val valor: BigDecimal,
    val sacador: Parte,
    val sacado: Parte,
    val informacoesPagamento: InformacoesPagamento,
)
