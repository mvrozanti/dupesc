package io.dupesc.infrastructure.db

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

@Component
class AdvisoryLockManager(private val dataSource: DataSource) {

    private val log = LoggerFactory.getLogger(AdvisoryLockManager::class.java)

    fun <T> comLock(lockId: Long, bloco: () -> T): T? {
        val conn = dataSource.connection
        var adquirido = false
        var liberado = false
        return try {
            executar(conn, "SELECT pg_advisory_unlock_all()")
            adquirido = consultarBoolean(conn, "SELECT pg_try_advisory_lock(?)", lockId)
            if (adquirido) bloco() else null
        } finally {
            if (adquirido) {
                liberado = runCatching { consultarBoolean(conn, "SELECT pg_advisory_unlock(?)", lockId) }
                    .getOrElse {
                        log.error("falha ao liberar advisory lock {}", lockId, it)
                        false
                    }
            }
            if (adquirido && !liberado) {
                descartar(conn, lockId)
            } else {
                runCatching { conn.close() }
            }
        }
    }

    private fun descartar(conn: Connection, lockId: Long) {
        log.error("advisory lock {} nao foi liberado — descartando a conexao para nao vazar o lock no pool", lockId)
        val descartada = runCatching { dataSource.unwrap(HikariDataSource::class.java).evictConnection(conn) }
            .onFailure { log.error("nao foi possivel descartar a conexao do pool", it) }
            .isSuccess
        if (!descartada) {
            runCatching { executar(conn, "SELECT pg_terminate_backend(pg_backend_pid())") }
                .onFailure { log.error("nao foi possivel encerrar a sessao que segura o lock {}", lockId, it) }
        }
        runCatching { conn.close() }
    }

    private fun executar(conn: Connection, sql: String) {
        conn.prepareStatement(sql).use { it.executeQuery().close() }
    }

    private fun consultarBoolean(conn: Connection, sql: String, parametro: Long): Boolean =
        conn.prepareStatement(sql).use { st ->
            st.setLong(1, parametro)
            st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
}
