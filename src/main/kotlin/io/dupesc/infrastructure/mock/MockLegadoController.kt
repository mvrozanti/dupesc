package io.dupesc.infrastructure.mock

import io.dupesc.domain.model.InformacoesPagamentoLegado
import io.dupesc.domain.model.OperacaoLegado
import io.dupesc.domain.model.PaginaLegado
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

data class ReiniciarRequest(val total: Int)

@RestController
@Profile("mock-legado")
class MockLegadoController {

    private val total = AtomicInteger(0)

    @GetMapping("/legado/operacoes")
    fun operacoes(@RequestParam afterId: Long, @RequestParam tamanho: Int): PaginaLegado {
        val inicio = afterId + 1
        val fim = minOf(inicio + tamanho, total.get().toLong())
        val operacoes = (inicio until fim).map { n -> operacao(n) }
        return PaginaLegado(
            operacoes = operacoes,
            proximoCursor = if (fim > inicio) fim - 1 else null,
            temMais = fim < total.get(),
        )
    }

    @PostMapping("/mock/legado/reiniciar")
    fun reiniciar(@RequestBody corpo: ReiniciarRequest): ResponseEntity<Void> {
        total.set(corpo.total)
        return ResponseEntity.ok().build()
    }

    private fun operacao(n: Long): OperacaoLegado {
        val emissao = LocalDate.of(2026, 1, 1).plusDays(n)
        return OperacaoLegado(
            id = "OP-$n",
            duplicataId = n,
            tipo = "MERC",
            numeroFatura = "NF-$n",
            parcela = 1,
            assinatura = "assinatura-teste",
            emissao = emissao,
            vencimento = emissao.plusDays(30),
            valor = BigDecimal("1000.00").add(BigDecimal(n)),
            sacadorDocumento = "31619393000140",
            sacadorNome = "FIDC Gestora Ltda",
            sacadoDocumento = "39053344705",
            sacadoNome = "Sacado $n",
            sacadoEmail = "sacado$n@example.com",
            informacoesPagamento = InformacoesPagamentoLegado(
                tipoInstrumento = "PPIX",
                iban = null,
                chavePix = "sacado$n@example.com",
            ),
        )
    }
}
