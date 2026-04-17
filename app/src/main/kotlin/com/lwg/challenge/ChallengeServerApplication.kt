package com.lwg.challenge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ChallengeServerApplication

fun main(args: Array<String>) {
	runApplication<ChallengeServerApplication>(*args)
}
