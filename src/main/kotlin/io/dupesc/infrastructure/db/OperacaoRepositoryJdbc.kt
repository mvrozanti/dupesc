package io.dupesc.infrastructure.db

import io.dupesc.domain.model.EstadoOperacao
import io.dupesc.domain.repository.LoteEnviado
import io.dupesc.domain.repository.OperacaoLinha
import io.dupesc.domain.repository.OperacaoPresa
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

    override fun inserir(intencaoId: Long, referenciaExterna: String, registradora: String): Long =
        jdbc.sql(
            "INSERT INTO operacao (intencao_id, referencia_externa, registradora) VALUES (:intencao, :referencia, :registradora) RETURNING id",
        )
            .param("intencao", intencaoId)
            .param("referencia", referenciaExterna)
            .param("registradora", registradora)
            .query(Long::class.java)
            .single()!!

    override fun registrarLote(ids: List<Long>, loteId: String) {
        jdbc.sql(
            "UPDATE operacao SET lote_id = :lote, atualizado_em = now() WHERE id IN (:ids) AND estado = 'EM_ENVIO'",
        )
            .param("lote", loteId)
            .param("ids", ids)
            .update()
    }

    override fun marcarEnviado(ids: List<Long>, loteId: String) {
        jdbc.sql(
            """
            UPDATE operacao SET estado = 'ENVIADO', lote_id = :lote, enviado_em = now(), atualizado_em = now()
            WHERE id IN (:ids) AND estado = 'EM_ENVIO'
            """.trimIndent(),
        )
            .param("lote", loteId)
            .param("ids", ids)
            .update()
    }

    override fun falhaRetryavel(ids: List<Long>, erro: String) {
        jdbc.sql(
            "UPDATE operacao SET estado = 'PENDENTE', ultimo_erro = :erro, atualizado_em = now() WHERE id IN (:ids) AND estado = 'EM_ENVIO'",
        )
            .param("erro", erro)
            .param("ids", ids)
            .update()
    }

    override fun incrementarConsultas(ids: List<Long>) {
        jdbc.sql("UPDATE operacao SET consultas = consultas + 1 WHERE id IN (:ids) AND estado = 'ENVIADO'")
            .param("ids", ids)
            .update()
    }

    override fun marcarIndeterminado(ids: List<Long>, erro: String) {
        jdbc.sql(
            """
            UPDATE operacao SET estado = 'INDETERMINADO', ultimo_erro = :erro, atualizado_em = now()
            WHERE id IN (:ids) AND estado IN (:estados)
            """.trimIndent(),
        )
            .param("erro", erro)
            .param("ids", ids)
            .param("estados", estadosSql(EventoOperacao.Indeterminada("")))
            .update()
    }

    override fun buscarEnviadosPresos(consultasLimite: Int, maximo: Int): List<OperacaoPresa> =
        jdbc.sql(
            """
            SELECT id, referencia_externa, registradora, lote_id, consultas
            FROM operacao WHERE estado = 'ENVIADO' AND consultas >= :limite
            ORDER BY id LIMIT :maximo
            """.trimIndent(),
        )
            .param("limite", consultasLimite)
            .param("maximo", maximo)
            .query { rs, _ -> OperacaoPresa(
                id = rs.getLong("id"),
                referenciaExterna = rs.getString("referencia_externa"),
                registradora = rs.getString("registradora"),
                loteId = rs.getString("lote_id"),
                consultas = rs.getInt("consultas"),
            ) }
            .list()

    override fun falhaPermanente(ids: List<Long>, erro: String) {
        jdbc.sql(
            """
            UPDATE operacao SET estado = 'FALHA_PERMANENTE', erros = jsonb_build_object('erro', :erros),
                                ultimo_erro = :erros, atualizado_em = now()
            WHERE id IN (:ids) AND estado IN (:estados)
            """.trimIndent(),
        )
            .param("erros", erro)
            .param("ids", ids)
            .param("estados", estadosSql(EventoOperacao.FalhaPermanente("")))
            .update()
    }

    override fun marcarRegistrado(id: Long, iud: String): Boolean =
        jdbc.sql(
            """
            UPDATE operacao SET estado = 'REGISTRADO', operation_id = :iud, atualizado_em = now()
            WHERE id = :id AND estado IN (:estados) AND (operation_id IS NULL OR operation_id = :iud)
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
            SELECT o.id, o.referencia_externa, o.estado, o.intencao_id, o.lote_id, i.duplicata_id
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
                loteId = rs.getString("lote_id"),
            ) }
            .optional()
            .orElse(null)

    override fun buscarLotesEnviados(idadeMin: Instant, limite: Int): List<LoteEnviado> =
        jdbc.sql(
            """
            SELECT registradora, lote_id, array_agg(id ORDER BY id) AS ids
            FROM operacao
            WHERE estado = 'ENVIADO' AND lote_id IS NOT NULL AND enviado_em < :idadeMin
            GROUP BY registradora, lote_id
            ORDER BY min(enviado_em)
            LIMIT :limite
            """.trimIndent(),
        )
            .param("idadeMin", OffsetDateTime.ofInstant(idadeMin, ZoneOffset.UTC))
            .param("limite", limite)
            .query { rs, _ ->
                val ids = (rs.getArray("ids").array as Array<*>).map { (it as Number).toLong() }
                LoteEnviado(rs.getString("registradora"), rs.getString("lote_id"), ids)
            }
            .list()

    override fun reprocessar(id: Long): Boolean =
        jdbc.sql("UPDATE operacao SET estado = 'PENDENTE', atualizado_em = now() WHERE id = :id AND estado = 'FALHA_PERMANENTE'")
            .param("id", id)
            .update() == 1

    private fun estadosSql(evento: EventoOperacao): Set<String> =
        StateMachine.origens(evento).map { it.name }.toSet()
}
