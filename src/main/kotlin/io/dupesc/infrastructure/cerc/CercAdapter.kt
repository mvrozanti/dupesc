package io.dupesc.infrastructure.cerc

import io.dupesc.domain.model.ConsultaResultado
import io.dupesc.domain.model.EnvioHandle
import io.dupesc.domain.model.ErroRegistradora
import io.dupesc.domain.model.ItemResultado
import io.dupesc.domain.model.ProcessamentoEstado
import io.dupesc.domain.model.RegistroComando
import io.dupesc.domain.port.RegistradoraPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component("registradoraBruta")
@ConditionalOnProperty(name = ["dupe.cerc.enabled"], havingValue = "true", matchIfMissing = true)
class CercAdapter(private val client: CercClient) : RegistradoraPort {

    private val log = LoggerFactory.getLogger(CercAdapter::class.java)

    override fun enviar(comandos: List<RegistroComando>): EnvioHandle =
        EnvioHandle(client.enviarLote(CercPayloadMapper.itens(comandos)).id)

    override fun consultar(handle: EnvioHandle): ConsultaResultado {
        val status = client.consultarStatus(handle.id)
        val processados = status.lista_itens_processados.orEmpty().mapNotNull {
            if (it.referencia_externa.isNullOrBlank()) return@mapNotNull null
            ItemResultado(
                referenciaExterna = it.referencia_externa,
                estado = ProcessamentoEstado.PROCESSADO,
                operationId = it.identificador_item_processado,
            )
        }
        val invalidos = status.lista_itens_invalidos.orEmpty().mapNotNull {
            if (it.referencia_externa.isNullOrBlank()) return@mapNotNull null
            ItemResultado(
                referenciaExterna = it.referencia_externa,
                estado = ProcessamentoEstado.REJEITADO,
                erros = it.erros.orEmpty().map { e ->
                    ErroRegistradora(e.codigo, e.mensagem ?: "sem mensagem")
                },
            )
        }
        return ConsultaResultado(
            handle = handle,
            statusLote = parseStatus(status.status),
            itens = processados + invalidos,
        )
    }

    private fun parseStatus(status: String): ProcessamentoEstado =
        runCatching { ProcessamentoEstado.valueOf(status.uppercase()) }
            .getOrElse {
                log.warn("status desconhecido da CERC '{}' tratado como PROCESSANDO", status)
                ProcessamentoEstado.PROCESSANDO
            }
}
