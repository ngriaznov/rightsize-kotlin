package dev.rightsize.examples

/*
 * Redis quickstart — the plain API, no JUnit extension.
 *
 * Shows the smallest possible rightsize round-trip: boot a real Redis container as a microVM
 * (or Docker container, depending on backend), connect to it with a normal Redis client, do one
 * SET/GET, then tear it down.
 *
 * Run it with:
 *   ./gradlew :examples:runRedis
 *
 * Force a specific backend:
 *   RIGHTSIZE_BACKEND=microsandbox ./gradlew :examples:runRedis
 *   RIGHTSIZE_BACKEND=docker       ./gradlew :examples:runRedis
 */

import dev.rightsize.modules.RedisContainer
import io.lettuce.core.RedisClient

fun main() {
    val redis = RedisContainer()
    redis.start()
    try {
        println("Redis is up at ${redis.uri}")

        val client = RedisClient.create(redis.uri)
        client.connect().use { connection ->
            val commands = connection.sync()
            commands.set("greeting", "hello from rightsize")
            val value = commands.get("greeting")
            println("GET greeting -> $value")
            check(value == "hello from rightsize") { "unexpected value read back from Redis" }
        }
        client.shutdown()
    } finally {
        redis.stop()
    }
}
