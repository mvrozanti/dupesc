package io.dupesc.infrastructure.db

import org.slf4j.LoggerFactory
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class AdvisoryLockManager(private val dataSource: DataSource) {

    private val log = LoggerFactory.getLogger(AdvisoryLockManager::class.java)

    fun <T> comLock(lockId: Long, bloco: () -> T): T? {
        val conn = DataSourceUtils.getConnection(dataSource)
        var adquirido = false
        return try {
            adquirido = conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { st ->
                st.setLong(1, lockId)
                st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (adquirido) bloco() else null
        } finally {
            if (adquirido) {
                try {
                    conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { st ->
                        st.setLong(1, lockId)
                        st.executeQuery().close()
                    }
                } catch (e: Exception) {
                    log.warn("falha ao liberar advisory lock {}", lockId, e)
                }
            }
            DataSourceUtils.releaseConnection(conn, dataSource)
        }
    }
}
