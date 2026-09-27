package io.dupesc.infrastructure.configuration

import io.dupesc.domain.service.RetryPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class BeansConfig {

    @Bean
    fun restClientBuilder(): RestClient.Builder {
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val factory = JdkClientHttpRequestFactory(httpClient)
        factory.setReadTimeout(Duration.ofSeconds(10))
        return RestClient.builder().requestFactory(factory)
    }

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
