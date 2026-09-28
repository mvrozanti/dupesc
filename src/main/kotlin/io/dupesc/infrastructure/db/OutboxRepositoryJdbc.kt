package io.dupesc.infrastructure.db

import com.fasterxml.jackson.databind.ObjectMapper
import io.dupesc.domain.model.OperacaoLegado
import io.dupesc.domain.repository.Claim
import io.dupesc.domain.repository.ItemComando
import io.dupesc.domain.repository.OutboxRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class OutboxRepositoryJdbc(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) : OutboxRepository {

    override fun inserir(operacaoId: Long) {
        jdbc.sql("INSERT INTO outbox (operacao_id) VALUES (:operacao)")
            .param("operacao", operacaoId)
            .update()
    }

    override fun reivindicar(tamanho: Int, podId: String, leaseMs: Long): List<Claim> =
        jdbc.sql(
            """
            WITH lote AS (
                SELECT o.id AS outbox_id, o.operacao_id
                FROM outbox o
                JOIN operacao op ON op.id = o.operacao_id
                WHERE o.status = 'PENDENTE' AND o.next_attempt_at <= now() AND op.estado = 'PENDENTE'
                ORDER BY o.next_attempt_at
                FOR UPDATE OF o SKIP LOCKED
                LIMIT :tamanho
            )
            UPDATE outbox o SET status = 'EM_ENVIO', claimed_by = :pod,
                                claimed_until = now() + make_interval(secs => :leaseSeg),
                                attempt_count = o.attempt_count + 1, atualizado_em = now()
            FROM lote WHERE o.id = lote.outbox_id
            RETURNING o.operacao_id, o.attempt_count, o.claimed_until
            """.trimIndent(),
        )
            .param("tamanho", tamanho)
            .param("pod", podId)
            .param("leaseSeg", leaseMs / 1000.0)
            .query { rs, _ -> Claim(
                operacaoId = rs.getLong("operacao_id"),
                attemptCount = rs.getInt("attempt_count"),
                claimedUntil = rs.getTimestamp("claimed_until").toInstant(),
            ) }
            .list()
            .also { ids ->
                if (ids.isNotEmpty()) {
                    jdbc.sql("UPDATE operacao SET estado = 'EM_ENVIO', atualizado_em = now() WHERE id IN (:ids) AND estado = 'PENDENTE'")
                        .param("ids", ids.map { it.operacaoId })
                        .update()
                }
            }

    override fun buscarComandos(operacaoIds: List<Long>): List<ItemComando> =
        jdbc.sql(
            """
            SELECT o.id AS operacao_id, op.referencia_externa, op.registradora, i.duplicata_id, i.dados::text AS dados
            FROM outbox o
            JOIN operacao op ON op.id = o.operacao_id
            JOIN intencao i ON i.id = op.intencao_id
            WHERE o.operacao_id IN (:ids)
            """.trimIndent(),
        )
            .param("ids", operacaoIds)
            .query { rs, _ -> ItemComando(
                operacaoId = rs.getLong("operacao_id"),
                referenciaExterna = rs.getString("referencia_externa"),
                duplicataId = rs.getLong("duplicata_id"),
                registradora = rs.getString("registradora"),
                operacaoLegado = objectMapper.readValue(rs.getString("dados"), OperacaoLegado::class.java),
            ) }
            .list()

    override fun marcarProcessado(operacaoIds: List<Long>) {
        jdbc.sql("UPDATE outbox SET status = 'PROCESSADO', atualizado_em = now() WHERE operacao_id IN (:ids) AND status = 'EM_ENVIO'")
            .param("ids", operacaoIds)
            .update()
    }

    override fun falhaRetryavel(operacaoIds: List<Long>, atrasoMs: Long, erro: String) {
        jdbc.sql(
            """
            UPDATE outbox SET status = 'PENDENTE',
                              next_attempt_at = now() + make_interval(secs => :atrasoSeg),
                              ultimo_erro = :erro,
                              claimed_by = NULL, claimed_until = NULL,
                              atualizado_em = now()
            WHERE operacao_id IN (:ids) AND status = 'EM_ENVIO'
            """.trimIndent(),
        )
            .param("atrasoSeg", atrasoMs / 1000.0)
            .param("erro", erro)
            .param("ids", operacaoIds)
            .update()
    }

    override fun marcarDlq(operacaoIds: List<Long>) {
        jdbc.sql("UPDATE outbox SET status = 'DLQ', atualizado_em = now() WHERE operacao_id IN (:ids) AND status = 'EM_ENVIO'")
            .param("ids", operacaoIds)
            .update()
    }

    override fun repararLeasesVencidos(): List<Long> =
        jdbc.sql(
            """
            UPDATE outbox o SET status = 'PENDENTE', next_attempt_at = now(), claimed_by = NULL, claimed_until = NULL
            FROM operacao op
            WHERE o.operacao_id = op.id AND o.status = 'EM_ENVIO' AND o.claimed_until < now() AND op.lote_id IS NULL
            RETURNING o.operacao_id
            """.trimIndent(),
        )
            .query(Long::class.java)
            .list()
            .also { ids ->
                if (ids.isNotEmpty()) {
                    jdbc.sql("UPDATE operacao SET estado = 'PENDENTE', atualizado_em = now() WHERE id IN (:ids) AND estado = 'EM_ENVIO'")
                        .param("ids", ids)
                        .update()
                }
            }

    override fun promoverEnviadosComLote(): List<Long> =
        jdbc.sql(
            """
            UPDATE outbox o SET status = 'PROCESSADO', atualizado_em = now()
            FROM operacao op
            WHERE o.operacao_id = op.id AND o.status = 'EM_ENVIO' AND o.claimed_until < now() AND op.lote_id IS NOT NULL
            RETURNING o.operacao_id
            """.trimIndent(),
        )
            .query(Long::class.java)
            .list()
            .also { ids ->
                if (ids.isNotEmpty()) {
                    jdbc.sql("UPDATE operacao SET estado = 'ENVIADO', enviado_em = now(), atualizado_em = now() WHERE id IN (:ids) AND estado = 'EM_ENVIO'")
                        .param("ids", ids)
                        .update()
                }
            }

    override fun idadePendenteMaisAntigoMs(): Long? =
        jdbc.sql(
            """
            SELECT extract(epoch FROM (now() - coalesce(reenfileirado_em, criado_em))) * 1000
            FROM outbox WHERE status = 'PENDENTE'
            ORDER BY coalesce(reenfileirado_em, criado_em) LIMIT 1
            """.trimIndent(),
        )
            .query { rs, _ -> rs.getDouble(1).toLong() }
            .optional()
            .orElse(null)

    override fun contarPendentes(): Long =
        jdbc.sql("SELECT count(*) FROM outbox WHERE status = 'PENDENTE'")
            .query(Long::class.java)
            .single()!!

    override fun reprocessar(operacaoId: Long): Boolean =
        jdbc.sql(
            """
            UPDATE outbox SET status = 'PENDENTE', next_attempt_at = now(), attempt_count = 0,
                              reenfileirado_em = now(), atualizado_em = now()
            WHERE operacao_id = :id AND status = 'DLQ'
            """.trimIndent(),
        )
            .param("id", operacaoId)
            .update() == 1
}
