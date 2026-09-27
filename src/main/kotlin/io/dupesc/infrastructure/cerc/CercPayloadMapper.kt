package io.dupesc.infrastructure.cerc

import io.dupesc.domain.model.RegistroComando

object CercPayloadMapper {

    fun itens(comandos: List<RegistroComando>): List<Map<String, Any?>> = comandos.map { item(it) }

    fun item(comando: RegistroComando): Map<String, Any?> = mapOf(
        "referencia_externa" to comando.referenciaExterna,
        "tipo" to comando.tipo,
        "identificador" to mapOf(
            "fatura" to comando.numeroFatura,
            "parcela" to comando.parcela,
        ),
        "partes" to mapOf(
            "sacador" to mapOf(
                "documento" to comando.sacador.documento,
                "razao_social" to comando.sacador.nome,
                "assinatura" to comando.assinatura,
            ),
            "sacado" to mapOf(
                "documento" to comando.sacado.documento,
                "nome" to comando.sacado.nome,
                "email" to comando.sacado.email,
            ),
        ),
        "informacoes_pagamento" to mapOf(
            "tipo_instrumento" to comando.informacoesPagamento.tipoInstrumento,
            "iban" to comando.informacoesPagamento.iban,
            "chave_pix" to comando.informacoesPagamento.chavePix,
        ),
        "valor_total_fatura" to comando.valor,
        "valor" to comando.valor,
        "emissao" to comando.emissao.toString(),
        "vencimento" to comando.vencimento.toString(),
    )
}
