package io.dupesc.application.service

import io.dupesc.domain.model.ConsultaResultado
import io.dupesc.domain.model.EnvioHandle
import io.dupesc.domain.model.RegistroComando
import io.dupesc.domain.port.RegistradoraException
import io.dupesc.domain.port.RegistradoraPort
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Primary
@Component
class ResilientRegistradora(
    @Qualifier("registradoraBruta")
    private val port: RegistradoraPort,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    rateLimiterRegistry: RateLimiterRegistry,
) : RegistradoraPort {

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("cerc")
    private val rateLimiter = rateLimiterRegistry.rateLimiter("cerc")

    override fun enviar(comandos: List<RegistroComando>): EnvioHandle = protegido { port.enviar(comandos) }

    override fun consultar(handle: EnvioHandle): ConsultaResultado = protegido { port.consultar(handle) }

    private fun <T> protegido(bloco: () -> T): T =
        try {
            CircuitBreaker.decorateSupplier(circuitBreaker, RateLimiter.decorateSupplier(rateLimiter, bloco)).get()
        } catch (e: CallNotPermittedException) {
            throw RegistradoraException("circuit breaker aberto", retryavel = true, naoEsgota = true)
        } catch (e: RequestNotPermitted) {
            throw RegistradoraException("rate limiter esgotado", retryavel = true, naoEsgota = true)
        }
}
