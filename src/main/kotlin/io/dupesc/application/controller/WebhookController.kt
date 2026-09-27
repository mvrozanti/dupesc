package io.dupesc.application.controller

import io.dupesc.application.service.WebhookService
import io.dupesc.infrastructure.configuration.WebhookAssinatura
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class WebhookController(
    private val webhookService: WebhookService,
    private val assinatura: WebhookAssinatura,
) {

    @PostMapping("/webhook/{registradora}")
    fun receber(
        @PathVariable registradora: String,
        @RequestHeader(value = "X-Signature", required = false) xSignature: String?,
        @RequestBody corpo: String,
    ): ResponseEntity<Void> {
        if (!assinatura.valida(registradora, corpo, xSignature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        webhookService.processar(registradora, corpo)
        return ResponseEntity.ok().build()
    }
}
