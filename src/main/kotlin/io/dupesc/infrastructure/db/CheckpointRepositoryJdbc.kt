package io.dupesc.infrastructure.db

import io.dupesc.domain.repository.CheckpointRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class CheckpointRepositoryJdbc(private val jdbc: JdbcClient) : CheckpointRepository {

    override fun buscar(nome: String): Long? =
        jdbc.sql("SELECT valor FROM checkpoint WHERE nome = :nome")
            .param("nome", nome)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    override fun avancar(nome: String, atualEsperado: Long, novo: Long): Boolean {
        val atualizado = jdbc.sql("UPDATE checkpoint SET valor = :novo, atualizado_em = now() WHERE nome = :nome AND valor = :atual")
            .param("nome", nome)
            .param("atual", atualEsperado)
            .param("novo", novo)
            .update()
        if (atualizado == 1) return true
        if (buscar(nome) == null) {
            return jdbc.sql("INSERT INTO checkpoint (nome, valor) VALUES (:nome, :novo) ON CONFLICT (nome) DO NOTHING")
                .param("nome", nome)
                .param("novo", novo)
                .update() == 1
        }
        return false
    }
}
