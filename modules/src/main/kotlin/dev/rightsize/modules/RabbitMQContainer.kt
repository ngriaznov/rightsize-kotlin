package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

/**
 * A single-node RabbitMQ container with the management plugin enabled. Defaults to a
 * `guest`/`guest` credential pair (the image's own default) so [amqpUrl] is usable with zero
 * configuration; call [withUsername]/[withPassword] before [start] to override either.
 *
 * ### Readiness — verified against a real 4.x boot
 *
 * `rabbitmq:4-management-alpine` still prints the same `"Server startup complete"` line the 3.x
 * series used (captured verbatim from a real boot with this module's env):
 *
 * ```
 * ...
 * 2026-07-04 08:47:17.936423+00:00 [info] <0.1036.0> started TCP listener on [::]:5672
 *  completed with 4 plugins.
 * 2026-07-04 08:47:18.001311+00:00 [info] <0.900.0> Server startup complete; 4 plugins started.
 * 2026-07-04 08:47:18.001311+00:00 [info] <0.900.0>  * rabbitmq_prometheus
 * 2026-07-04 08:47:18.001311+00:00 [info] <0.900.0>  * rabbitmq_management
 * 2026-07-04 08:47:18.001311+00:00 [info] <0.900.0>  * rabbitmq_management_agent
 * 2026-07-04 08:47:18.001311+00:00 [info] <0.900.0>  * rabbitmq_web_dispatch
 * ```
 *
 * The line appears exactly once, so `forLogMessage` at its default `times=1` is unambiguous —
 * unlike Postgres/MySQL/MariaDB, there is no double-boot restart to race here. The management
 * API's own `/api/health/checks/...` endpoints require authenticated requests, so the log line is
 * the simpler and equally reliable readiness signal.
 *
 * No control characters were found in the image's baked env (checked via `docker inspect`),
 * so no env override is needed here — unlike [PostgreSQLContainer].
 *
 * No `withMemoryLimit` override: booted clean on msb's default ~450M microVM RAM (observed ~5.5s
 * IT round-trip on both backends — an Erlang VM, not a JVM, so no Paketo/QuickStart-style heap
 * demand; no memory-ladder escalation was needed).
 *
 * ### A 4.x behavior change worth knowing (not this module's concern, but bites naive clients)
 *
 * RabbitMQ 4.x deprecates `transient_nonexcl_queues` and, per the broker's own startup warning,
 * "this feature can still be used for now" but a client that declares a **non-durable,
 * non-exclusive** queue (`durable=false, exclusive=false`) may be rejected with
 * `reply-code=541 INTERNAL_ERROR` depending on the deployed policy — reproduced directly against
 * this module's pinned image. Declare durable, non-exclusive queues (or exclusive transient ones)
 * from client code exercising this container; this module itself declares no queues.
 */
class RabbitMQContainer(image: String = "rabbitmq:4-management-alpine") :
    GenericContainer<RabbitMQContainer>(image) {
    private var usernameState = "guest"
    private var passwordState = "guest"

    init {
        withExposedPorts(AMQP_PORT, MANAGEMENT_PORT)
        withEnv("RABBITMQ_DEFAULT_USER", usernameState)
        withEnv("RABBITMQ_DEFAULT_PASS", passwordState)
        // Exactly-once log line (see the class doc for the captured excerpt) — no restart race.
        waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
    }

    /** Overrides `RABBITMQ_DEFAULT_USER` (default `guest`). */
    fun withUsername(username: String): RabbitMQContainer {
        usernameState = username
        return withEnv("RABBITMQ_DEFAULT_USER", username)
    }

    /** Overrides `RABBITMQ_DEFAULT_PASS` (default `guest`). */
    fun withPassword(password: String): RabbitMQContainer {
        passwordState = password
        return withEnv("RABBITMQ_DEFAULT_PASS", password)
    }

    /** The configured management/AMQP user (default `guest`). */
    val username: String get() = usernameState
    /** The configured management/AMQP password (default `guest`). */
    val password: String get() = passwordState

    /** An `amqp://` URL (with credentials) for the running container's AMQP listener. */
    val amqpUrl: String get() = "amqp://$usernameState:$passwordState@$host:${getMappedPort(AMQP_PORT)}"
    /** The management UI/API base URI for the running container. */
    val managementUrl: String get() = "http://$host:${getMappedPort(MANAGEMENT_PORT)}"

    private companion object {
        const val AMQP_PORT = 5672
        const val MANAGEMENT_PORT = 15672
    }
}
