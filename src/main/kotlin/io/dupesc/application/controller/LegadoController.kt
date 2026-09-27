package io.dupesc.application.controller

import io.dupesc.application.service.IngestaoService
import io.dupesc.domain.model.OperacaoLegado
import io.dupesc.domain.repository.OrigemDlq
import io.dupesc.infrastructure.configuration.DupeProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

@RestController
@RequestMapping("/api/legado")
class LegadoController(
    private val ingestaoService: IngestaoService,
    properties: DupeProperties,
) {

    private val apiKey = properties.legado.apiKey

    @PostMapping("/operacoes")
    fun receber(
        @RequestHeader(value = "X-Api-Key", required = false) chave: String?,
        @RequestBody operacoes: List<OperacaoLegado>,
    ): ResponseEntity<Map<String, Int>> {
        if (!chaveValida(chave)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (operacoes.size > LOTE_MAXIMO) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
        val novas = ingestaoService.gravar(operacoes, OrigemDlq.INGESTAO)
        return ResponseEntity.ok(mapOf("novas" to novas, "ja_existentes" to operacoes.size - novas))
    }

    private fun chaveValida(chave: String?): Boolean =
        chave != null && MessageDigest.isEqual(chave.toByteArray(), apiKey.toByteArray())

    companion object {
        const val LOTE_MAXIMO = 1000
    }
}
