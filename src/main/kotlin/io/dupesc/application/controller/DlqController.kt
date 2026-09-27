package io.dupesc.application.controller

import io.dupesc.domain.repository.DlqLinha
import io.dupesc.domain.repository.DlqRepository
import io.dupesc.domain.repository.OperacaoRepository
import io.dupesc.domain.repository.OutboxRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dlq")
class DlqController(
    private val dlqRepository: DlqRepository,
    private val operacaoRepository: OperacaoRepository,
    private val outboxRepository: OutboxRepository,
    private val transactionTemplate: TransactionTemplate,
) {

    @GetMapping
    fun listar(@RequestParam(defaultValue = "100") limite: Int): List<DlqLinha> =
        dlqRepository.listarAbertos(limite)

    @PostMapping("/{id}/reprocessar")
    fun reprocessar(@PathVariable id: Long): ResponseEntity<Map<String, String>> {
        val linha = dlqRepository.buscar(id)
            ?: return ResponseEntity.notFound().build()
        if (linha.origem != "OUTBOX") {
            return ResponseEntity.badRequest().body(mapOf("erro" to "origem ${linha.origem} nao reprocessavel"))
        }
        val referencia = linha.referencia
            ?: return ResponseEntity.badRequest().body(mapOf("erro" to "sem referencia"))
        val ok = transactionTemplate.execute {
            operacaoRepository.reprocessar(referencia) && outboxRepository.reprocessar(referencia)
        } ?: false
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("erro" to "estado nao permite reprocessar"))
        }
        transactionTemplate.executeWithoutResult { dlqRepository.marcarStatus(id, "REPROCESSADO") }
        return ResponseEntity.ok(mapOf("status" to "REPROCESSADO"))
    }
}
