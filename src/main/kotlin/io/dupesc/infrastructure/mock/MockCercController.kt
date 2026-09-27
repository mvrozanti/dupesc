package io.dupesc.infrastructure.mock

import io.dupesc.infrastructure.cerc.RetornoLote
import io.dupesc.infrastructure.cerc.StatusLote
import io.dupesc.infrastructure.cerc.TokenOauth
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("mock-cerc")
class MockCercController(private val estado: MockCercEstado) {

    @PostMapping("/oauth/token")
    fun token(): TokenOauth = TokenOauth("poc-token", 3600)

    @PostMapping("/v2/lote/escrituracao/duplicata/fatura")
    fun escriturar(@RequestBody itens: List<Map<String, Any?>>): ResponseEntity<RetornoLote> {
        when (estado.modo) {
            ModoMock.FORA_JANELA -> return ResponseEntity.status(423).build()
            ModoMock.LIMITE_TAXA -> return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .build()
            else -> Unit
        }
        return ResponseEntity.ok(RetornoLote(estado.criarLote(itens)))
    }

    @GetMapping("/v2/lote/{id}/status")
    fun status(@PathVariable id: String): ResponseEntity<StatusLote> {
        if (estado.modo == ModoMock.HANG_STATUS) {
            Thread.sleep(120_000)
        }
        return ResponseEntity.ok(estado.statusDe(id))
    }

    @PostMapping("/mock/cerc/modo")
    fun modo(@RequestBody corpo: ModoRequest): ResponseEntity<Void> {
        estado.modo = ModoMock.valueOf(corpo.modo.uppercase())
        estado.referenciaRejeitada = corpo.referenciaExterna
        return ResponseEntity.ok().build()
    }

    @PostMapping("/mock/cerc/replay")
    fun replay(@RequestBody corpo: ReplayRequest): ResponseEntity<Void> {
        estado.replay(corpo.eventId, corpo.vezes)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/mock/cerc/lotes")
    fun lotes(): List<LoteVisual> = estado.visual()
}
