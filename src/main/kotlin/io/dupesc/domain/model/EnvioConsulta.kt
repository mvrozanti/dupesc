package io.dupesc.domain.model

data class EnvioHandle(val id: String)

enum class ProcessamentoEstado { PROCESSANDO, PROCESSADO, REJEITADO, ERRO }

data class ErroRegistradora(val codigo: String?, val mensagem: String)

data class ItemResultado(
    val referenciaExterna: String,
    val estado: ProcessamentoEstado,
    val operationId: String? = null,
    val erros: List<ErroRegistradora> = emptyList(),
)

data class ConsultaResultado(
    val handle: EnvioHandle,
    val statusLote: ProcessamentoEstado,
    val itens: List<ItemResultado>,
)
