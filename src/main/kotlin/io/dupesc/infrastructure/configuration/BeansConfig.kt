package io.dupesc.infrastructure.configuration

import io.dupesc.domain.service.RetryPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class BeansConfig {

    @Bean
    fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(transactionManager)

    @Bean
    fun retryPolicy(properties: DupeProperties): RetryPolicy =
        RetryPolicy(
            baseMs = properties.retry.baseMs,
            capMs = properties.retry.capMs,
            maxAttempts = properties.retry.maxAttempts,
        )
}
