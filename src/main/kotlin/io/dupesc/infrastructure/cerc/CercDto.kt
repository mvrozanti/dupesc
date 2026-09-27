package io.dupesc.infrastructure.cerc

data class RetornoLote(val id: String)

data class StatusLote(
    val id: String,
    val status: String,
    val lista_itens_processados: List<ItemProcessadoLote>? = null,
    val lista_itens_invalidos: List<ItemInvalidoLote>? = null,
)

data class ItemProcessadoLote(val referencia_externa: String?, val identificador_item_processado: String?)

data class ItemInvalidoLote(val referencia_externa: String?, val erros: List<ErroLote>?)

data class ErroLote(val codigo: String?, val mensagem: String?)

data class TokenOauth(val access_token: String, val expires_in: Long)
