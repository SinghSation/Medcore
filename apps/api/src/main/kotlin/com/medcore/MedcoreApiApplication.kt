package com.medcore

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MedcoreApiApplication

fun main(args: Array<String>) {
    runApplication<MedcoreApiApplication>(*args)
}
