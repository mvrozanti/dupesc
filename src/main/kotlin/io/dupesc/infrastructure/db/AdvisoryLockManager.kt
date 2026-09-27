package io.dupesc.infrastructure.db

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class AdvisoryLockManager(
    private val jdbc: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
) {

    fun <T> comLock(lockId: Long, bloco: () -> T): T? = transactionTemplate.execute {
        val obtido = jdbc.sql("SELECT pg_try_advisory_xact_lock(:lock)")
            .param("lock", lockId)
            .query(Boolean::class.java)
            .single()!!
        if (obtido) bloco() else null
    }
}
