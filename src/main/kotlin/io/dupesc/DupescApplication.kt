package io.dupesc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class DupescApplication

fun main(args: Array<String>) {
    runApplication<DupescApplication>(*args)
}
