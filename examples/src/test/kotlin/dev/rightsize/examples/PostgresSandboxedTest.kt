package dev.rightsize.examples

/*
 * JUnit @Sandboxed example — a PostgreSQL container managed entirely by the extension.
 *
 * Shows the Testcontainers-shaped JUnit 5 style: annotate the test class with @Sandboxed, mark a
 * container field with @Container, and rightsize starts it before the class's tests run and
 * stops it after — no start()/stop() calls in the test body at all. Connects with the plain JDBC
 * driver and does one INSERT + SELECT.
 *
 * Run it with:
 *   ./gradlew :examples:test --tests "dev.rightsize.examples.PostgresSandboxedTest"
 *
 * (Tagged sandbox-it, like every other integration test in this repo, so it also runs as part of
 * `./gradlew :examples:test` here — examples/ runs its whole suite as integration tests, since
 * every example needs a real backend.)
 *
 * Force a specific backend:
 *   RIGHTSIZE_BACKEND=microsandbox ./gradlew :examples:test
 *   RIGHTSIZE_BACKEND=docker       ./gradlew :examples:test
 */

import dev.rightsize.junit.Container
import dev.rightsize.junit.Sandboxed
import dev.rightsize.modules.PostgreSQLContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager

@Tag("sandbox-it")
@Sandboxed
class PostgresSandboxedTest {
    companion object {
        @JvmStatic
        @Container
        val postgres = PostgreSQLContainer()
    }

    @Test
    fun `insert then select a row over plain JDBC`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE greeting (message TEXT NOT NULL)")
                st.execute("INSERT INTO greeting (message) VALUES ('hello from rightsize')")
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT message FROM greeting").use { rs ->
                    assertEquals(true, rs.next())
                    assertEquals("hello from rightsize", rs.getString("message"))
                }
            }
        }
    }
}
