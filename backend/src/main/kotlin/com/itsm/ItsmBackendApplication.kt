package com.itsm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ItsmBackendApplication

fun main(args: Array<String>) {
	runApplication<ItsmBackendApplication>(*args)
}
