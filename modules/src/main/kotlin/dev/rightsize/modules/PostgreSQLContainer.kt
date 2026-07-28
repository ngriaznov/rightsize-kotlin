package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-node PostgreSQL container. Defaults to a `test`/`test`/`test` user/password/database
 * trio so [jdbcUrl] is usable with zero configuration; call [withUsername]/[withPassword]/
 * [withDatabase] before [start] to override any of them.
 *
 * ### Defaults to `postgres:latest` — this image's floating reference, now Debian-based
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins, so the version moves with Postgres's own releases instead of this library's
 * release cycle. This module previously pinned `postgres:18-alpine`; `latest` is the Debian-based
 * variant, not Alpine — functionally equivalent for everything this module exercises, just a
 * larger pull. Pass that image explicitly to pin it: `PostgreSQLContainer("postgres:18-alpine")`.
 */
class PostgreSQLContainer(image: DockerImageName) : GenericContainer<PostgreSQLContainer>(image.toString()) {
    /** Defaults to `postgres:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "postgres:latest") : this(DockerImageName.parse(image))

    private var usernameState = "test"
    private var passwordState = "test"
    private var databaseState = "test"

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(5432)
        withEnv("POSTGRES_USER", usernameState)
        withEnv("POSTGRES_PASSWORD", passwordState)
        withEnv("POSTGRES_DB", databaseState)
        // The official postgres:*-alpine image bakes DOCKER_PG_LLVM_DEPS into its manifest with a
        // literal tab character in the value (a package-list built with `\t\t` continuation). msb
        // 0.6.2's krun VMM builder panics with InvalidAscii on that boot-env value before the guest
        // ever starts (reproduced with zero rightsize-set env vars — it's the image, not us).
        // Docker is unaffected. Overriding the var here wins over the image default in both
        // backends' env-merge order and is a no-op for the build the image already baked, so it's
        // a safe, backend-portable fix rather than an msb-only special case. This override was
        // verified against the alpine variant specifically; whether the Debian-based `latest` this
        // module now defaults to bakes the same tab-containing value has not been re-verified, so
        // the override is left in place unconditionally rather than gated on the image chosen.
        withEnv("DOCKER_PG_LLVM_DEPS", "")
        // The postgres entrypoint starts the server once to run initdb scripts against it, shuts
        // it down, then starts it again for real — printing "database system is ready to accept
        // connections" BOTH times. Waiting for the first occurrence races that restart: a client
        // can connect to the init-time server just before it's torn down. times=2 waits for the
        // second, durable listen.
        // 120s: the init-boot-then-real-boot sequence can exceed the default 60s on shared
        // CI runners.
        waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
            .withStartupTimeout(Duration.ofSeconds(120)))
    }

    /** Overrides `POSTGRES_USER` (default `test`). */
    fun withUsername(username: String): PostgreSQLContainer {
        usernameState = username
        return withEnv("POSTGRES_USER", username)
    }

    /** Overrides `POSTGRES_PASSWORD` (default `test`). */
    fun withPassword(password: String): PostgreSQLContainer {
        passwordState = password
        return withEnv("POSTGRES_PASSWORD", password)
    }

    /** Overrides `POSTGRES_DB` (default `test`). */
    fun withDatabase(database: String): PostgreSQLContainer {
        databaseState = database
        return withEnv("POSTGRES_DB", database)
    }

    /** The configured database user (default `test`). */
    val username: String get() = usernameState
    /** The configured database password (default `test`). */
    val password: String get() = passwordState
    /** The configured database name (default `test`). */
    val databaseName: String get() = databaseState

    /** A `jdbc:postgresql://` URL for the running container's [databaseName]. */
    val jdbcUrl: String get() = "jdbc:postgresql://$host:${getMappedPort(5432)}/$databaseName"

    private companion object {
        const val EXPECTED_REPOSITORY = "postgres"
    }
}
