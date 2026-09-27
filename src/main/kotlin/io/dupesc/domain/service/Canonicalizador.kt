package io.dupesc.domain.service

import io.dupesc.domain.model.InformacoesPagamento
import io.dupesc.domain.model.OperacaoLegado
import io.dupesc.domain.model.Parte
import io.dupesc.domain.model.RegistroComando

class DadoInvalidoException(message: String) : RuntimeException(message)

object Canonicalizador {

    private val documentoRegex = Regex("^\\d{11}$|^\\d{14}$")

    fun de(operacao: OperacaoLegado, referenciaExterna: String): RegistroComando {
        val duplicataId = operacao.duplicataId ?: throw DadoInvalidoException("duplicataId ausente")
        val tipo = operacao.tipo?.uppercase()?.takeIf { it in setOf("MERC", "SERV") }
            ?: throw DadoInvalidoException("tipo ausente ou invalido")
        val numeroFatura = operacao.numeroFatura?.takeIf { it.isNotBlank() && it.length <= 60 }
            ?: throw DadoInvalidoException("numero da fatura ausente ou invalido")
        val parcela = operacao.parcela?.takeIf { it >= 1 } ?: throw DadoInvalidoException("parcela ausente ou invalida")
        val assinatura = operacao.assinatura?.takeIf { it.isNotBlank() }
            ?: throw DadoInvalidoException("assinatura do sacador ausente")
        val emissao = operacao.emissao ?: throw DadoInvalidoException("emissao ausente")
        val vencimento = operacao.vencimento ?: throw DadoInvalidoException("vencimento ausente")
        val valor = operacao.valor ?: throw DadoInvalidoException("valor ausente")
        if (vencimento <= emissao) throw DadoInvalidoException("vencimento anterior ou igual a emissao")
        if (valor.signum() <= 0) throw DadoInvalidoException("valor nao positivo")
        val sacador = parte("sacador", operacao.sacadorDocumento, operacao.sacadorNome, null)
        val sacado = parte("sacado", operacao.sacadoDocumento, operacao.sacadoNome, operacao.sacadoEmail)
        val tipoInstrumento = operacao.informacoesPagamento?.tipoInstrumento
            ?: throw DadoInvalidoException("tipo de instrumento de pagamento ausente")
        return RegistroComando(
            referenciaExterna = referenciaExterna,
            duplicataId = duplicataId,
            tipo = tipo,
            numeroFatura = numeroFatura,
            parcela = parcela,
            assinatura = assinatura,
            emissao = emissao,
            vencimento = vencimento,
            valor = valor.setScale(2, java.math.RoundingMode.HALF_UP),
            sacador = sacador,
            sacado = sacado,
            informacoesPagamento = InformacoesPagamento(
                tipoInstrumento = tipoInstrumento,
                iban = operacao.informacoesPagamento.iban,
                chavePix = operacao.informacoesPagamento.chavePix,
            ),
        )
    }

    private fun parte(papel: String, documento: String?, nome: String?, email: String?): Parte {
        if (documento == null || !documentoRegex.matches(documento)) {
            throw DadoInvalidoException("documento de $papel ausente ou invalido")
        }
        if (nome.isNullOrBlank()) throw DadoInvalidoException("nome de $papel ausente")
        return Parte(documento = documento, nome = nome, email = email)
    }
}
