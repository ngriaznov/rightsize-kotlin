package dev.rightsize.modules

import io.lettuce.core.RedisClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Real round-trips for the Valkey, MinIO, and Cassandra modules. */
@Tag("sandbox-it")
class ValkeyMinIOCassandraIT {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun valkey() {
        val valkey = ValkeyContainer(); valkey.start()
        try {
            RedisClient.create(valkey.uri).connect().use { conn ->
                conn.sync().set("k", "v")
                assertEquals("v", conn.sync().get("k"))
            }
        } finally { valkey.stop() }
    }

    @Test fun minio() {
        val minio = MinIOContainer(); minio.start()
        try {
            val mcHost = "MC_HOST_local=http://${minio.username}:${minio.password}@127.0.0.1:9000"

            val mb = minio.execInContainer("sh", "-c", "$mcHost mc mb local/roundtrip")
            assertEquals(0, mb.exitCode, "mc mb failed: ${mb.stderr}")

            // Writes a guest file and uploads it with `mc cp` rather than feeding `mc pipe`
            // over stdin: `mc pipe` reads stdin, and an exec'd `mc pipe` under the
            // microsandbox backend either dumps its goroutines and exits non-zero or hangs
            // outright (observed both, directly). `mc cp` needs no stdin and round-trips
            // reliably. /srv, not /tmp — the guest mounts /tmp as a small tmpfs.
            val put = minio.execInContainer("sh", "-c", "printf 'hello minio' > /srv/hello.txt && $mcHost mc cp /srv/hello.txt local/roundtrip/hello.txt")
            assertEquals(0, put.exitCode, "mc cp failed: ${put.stderr}")

            val cat = minio.execInContainer("sh", "-c", "$mcHost mc cat local/roundtrip/hello.txt")
            assertEquals(0, cat.exitCode, "mc cat failed: ${cat.stderr}")
            assertEquals("hello minio", cat.stdout.trim())

            val live = http.send(
                HttpRequest.newBuilder(URI("${minio.endpointUrl}/minio/health/live")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, live.statusCode())

            val anon = http.send(
                HttpRequest.newBuilder(URI(minio.endpointUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertNotEquals(200, anon.statusCode(), "unauthenticated request to / should not succeed")
            assertTrue(anon.body().contains("AccessDenied"), "expected AccessDenied, got: ${anon.body()}")
        } finally { minio.stop() }
    }

    @Test fun cassandra() {
        val cassandra = CassandraContainer(); cassandra.start()
        try {
            val cql = """
                CREATE KEYSPACE roundtrip WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
                CREATE TABLE roundtrip.t (x int PRIMARY KEY);
                INSERT INTO roundtrip.t (x) VALUES (1);
                SELECT x FROM roundtrip.t;
            """.trimIndent().replace("\n", " ")
            // --request-timeout=60: cqlsh's ~10s per-request default is too tight for the
            // FIRST statement on a cold Cassandra JVM on a loaded CI runner.
            val result = cassandra.execInContainer("cqlsh", "--request-timeout=60", "-e", cql)
            assertEquals(0, result.exitCode, "cqlsh round-trip failed: ${result.stderr}")
            assertTrue(result.stdout.contains("1"), "expected the inserted row back, got: ${result.stdout}")
        } finally { cassandra.stop() }
    }
}
