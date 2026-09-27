package io.dupesc

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["dupe.scheduling.enabled=false"],
)
abstract class PostgresTestBase {

    companion object {
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("dupesc")
            .withUsername("dupesc")
            .withPassword("dupesc")
            .also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
