package io.dupesc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DupescApplication

fun main(args: Array<String>) {
    runApplication<DupescApplication>(*args)
}
