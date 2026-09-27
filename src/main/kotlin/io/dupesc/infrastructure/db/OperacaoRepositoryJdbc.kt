package io.dupesc.infrastructure.db

import io.dupesc.domain.model.EstadoOperacao
import io.dupesc.domain.repository.LoteEnviado
import io.dupesc.domain.repository.OperacaoLinha
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.service.EventoOperacao
import io.dupesc.domain.service.StateMachine
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class OperacaoRepositoryJdbc(private val jdbc: JdbcClient) : OperacaoRepository {

    override fun inserir(intencaoId: Long, referenciaExterna: String): Long =
        jdbc.sql(
            "INSERT INTO operacao (intencao_id, referencia_externa) VALUES (:intencao, :referencia) RETURNING id",
        )
            .param("intencao", intencaoId)
            .param("referencia", referenciaExterna)
            .query(Long::class.java)
            .single()!!

    override fun marcarEnviado(ids: List<Long>, loteId: String) {
        jdbc.sql(
            """
            UPDATE operacao SET estado = 'ENVIADO', lote_id = :lote, enviado_em = now(), atualizado_em = now()
            WHERE id = ANY (:ids) AND estado = 'EM_ENVIO'
            """.trimIndent(),
        )
            .param("lote", loteId)
            .param("ids", ids.toTypedArray())
            .update()
    }

    override fun falhaRetryavel(ids: List<Long>, erro: String, zeraTentativas: Boolean) {
        jdbc.sql(
            "UPDATE operacao SET estado = 'PENDENTE', atualizado_em = now() WHERE id = ANY (:ids) AND estado = 'EM_ENVIO'",
        )
            .param("ids", ids.toTypedArray())
            .update()
    }

    override fun falhaPermanente(ids: List<Long>, erro: String) {
        jdbc.sql(
            """
            UPDATE operacao SET estado = 'FALHA_PERMANENTE', erros = CAST(:erros AS jsonb), atualizado_em = now()
            WHERE id = ANY (:ids) AND estado IN (:estados)
            """.trimIndent(),
        )
            .param("erros", erro)
            .param("ids", ids.toTypedArray())
            .param("estados", estadosSql(EventoOperacao.FalhaPermanente("")))
            .update()
    }

    override fun resetarParaPendente(ids: List<Long>) {
        jdbc.sql(
            "UPDATE operacao SET estado = 'PENDENTE', lote_id = NULL, atualizado_em = now() WHERE id = ANY (:ids) AND estado = 'ENVIADO'",
        )
            .param("ids", ids.toTypedArray())
            .update()
    }

    override fun marcarRegistrado(id: Long, iud: String): Boolean =
        jdbc.sql(
            """
            UPDATE operacao SET estado = 'REGISTRADO', operation_id = :iud, atualizado_em = now()
            WHERE id = :id AND estado IN (:estados)
            """.trimIndent(),
        )
            .param("iud", iud)
            .param("id", id)
            .param("estados", estadosSql(EventoOperacao.Processada("")))
            .update() == 1

    override fun marcarRecusado(id: Long, errosJson: String): Boolean =
        jdbc.sql(
            """
            UPDATE operacao SET estado = 'RECUSADO', erros = CAST(:erros AS jsonb), atualizado_em = now()
            WHERE id = :id AND estado IN (:estados)
            """.trimIndent(),
        )
            .param("erros", errosJson)
            .param("id", id)
            .param("estados", estadosSql(EventoOperacao.Recusada(emptyList())))
            .update() == 1

    override fun buscarPorReferencia(referenciaExterna: String): OperacaoLinha? =
        jdbc.sql(
            """
            SELECT o.id, o.referencia_externa, o.estado, o.intencao_id, i.duplicata_id
            FROM operacao o JOIN intencao i ON i.id = o.intencao_id
            WHERE o.referencia_externa = :referencia
            """.trimIndent(),
        )
            .param("referencia", referenciaExterna)
            .query { rs, _ -> OperacaoLinha(
                id = rs.getLong("id"),
                referenciaExterna = rs.getString("referencia_externa"),
                estado = EstadoOperacao.valueOf(rs.getString("estado")),
                intencaoId = rs.getLong("intencao_id"),
                duplicataId = rs.getLong("duplicata_id"),
            ) }
            .optional()
            .orElse(null)

    override fun buscarLotesEnviados(idadeMin: Instant): List<LoteEnviado> =
        jdbc.sql(
            """
            SELECT lote_id, array_agg(id ORDER BY id) AS ids
            FROM operacao
            WHERE estado = 'ENVIADO' AND lote_id IS NOT NULL AND enviado_em < :idadeMin
            GROUP BY lote_id
            """.trimIndent(),
        )
            .param("idadeMin", OffsetDateTime.ofInstant(idadeMin, ZoneOffset.UTC))
            .query { rs, _ ->
                val ids = (rs.getArray("ids").array as Array<*>).map { (it as Number).toLong() }
                LoteEnviado(rs.getString("lote_id"), ids)
            }
            .list()

    override fun contarPendentes(): Long =
        jdbc.sql("SELECT count(*) FROM outbox WHERE status = 'PENDENTE'")
            .query(Long::class.java)
            .single()!!

    private fun estadosSql(evento: EventoOperacao): Array<String> =
        StateMachine.origens(evento).map { it.name }.toTypedArray()
}
