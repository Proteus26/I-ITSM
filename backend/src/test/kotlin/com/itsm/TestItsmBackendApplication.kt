package com.itsm

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<ItsmBackendApplication>().with(TestcontainersConfiguration::class).run(*args)
}
