package io.dupesc.domain.model

enum class EstadoOperacao {
    PENDENTE,
    EM_ENVIO,
    ENVIADO,
    REGISTRADO,
    RECUSADO,
    FALHA_PERMANENTE,
    INDETERMINADO,
    ;

    fun terminal() =
        this == REGISTRADO || this == RECUSADO || this == FALHA_PERMANENTE || this == INDETERMINADO
}

enum class StatusOutbox {
    PENDENTE,
    EM_ENVIO,
    PROCESSADO,
    DLQ,
}
