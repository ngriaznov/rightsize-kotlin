package dev.rightsize.modules

import com.rabbitmq.client.ConnectionFactory
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration

/** Real round-trips for the RabbitMQ, MariaDB, WireMock, and ClickHouse modules. */
@Tag("sandbox-it")
class RabbitMqMariaDbWireMockClickHouseIT {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun rabbitmq() {
        val rabbit = RabbitMQContainer(); rabbit.start()
        try {
            val factory = ConnectionFactory()
            factory.setUri(rabbit.amqpUrl)
            factory.newConnection().use { conn ->
                conn.createChannel().use { ch ->
                    // RabbitMQ 4.x deprecates (and, per its own server message, no longer permits
                    // by default) `transient_nonexcl_queues` — durable=false, exclusive=false
                    // queues get rejected with reply-code 541 INTERNAL_ERROR. Declare a durable,
                    // non-exclusive queue instead (the RabbitMQ-4.x-appropriate shape).
                    ch.queueDeclare("q1", true, false, false, null)
                    ch.basicPublish("", "q1", null, "hello".toByteArray())
                    // basicGet can briefly race the broker's own publish-to-queue enqueue; poll.
                    var delivery: com.rabbitmq.client.GetResponse? = null
                    val deadline = System.currentTimeMillis() + 5000
                    while (delivery == null && System.currentTimeMillis() < deadline) {
                        delivery = ch.basicGet("q1", true)
                        if (delivery == null) Thread.sleep(100)
                    }
                    assertNotNull(delivery, "never received the published message")
                    assertEquals("hello", String(delivery!!.body))
                }
            }
        } finally { rabbit.stop() }
    }

    @Test fun mariadb() {
        val mariadb = MariaDBContainer(); mariadb.start()
        try {
            DriverManager.getConnection(mariadb.jdbcUrl, mariadb.username, mariadb.password).use { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE t (x INT)") }
                conn.createStatement().use { it.execute("INSERT INTO t (x) VALUES (1)") }
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT x FROM t").use { rs ->
                        assertTrue(rs.next()); assertEquals(1, rs.getInt("x"))
                    }
                }
            }
        } finally { mariadb.stop() }
    }

    @Test fun wiremock() {
        val wm = WireMockContainer(); wm.start()
        try {
            val stub = """
                {"request":{"method":"GET","urlPath":"/hello"},
                 "response":{"status":200,"body":"world"}}
            """.trimIndent()
            val postResp = http.send(
                HttpRequest.newBuilder(URI("${wm.adminUrl}/mappings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(stub))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(201, postResp.statusCode(), "stub creation failed: ${postResp.body()}")

            val getResp = http.send(
                HttpRequest.newBuilder(URI("${wm.baseUrl}/hello")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getResp.statusCode())
            assertEquals("world", getResp.body())
        } finally { wm.stop() }
    }

    @Test fun clickhouse() {
        val ch = ClickHouseContainer(); ch.start()
        try {
            fun query(sql: String): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI(ch.httpUrl))
                    .header("Authorization", basicAuth(ch.username, ch.password))
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val create = query("CREATE TABLE t (x Int32) ENGINE=Memory")
            assertEquals(200, create.statusCode(), "CREATE TABLE failed: ${create.body()}")
            val insert = query("INSERT INTO t VALUES (1)")
            assertEquals(200, insert.statusCode(), "INSERT failed: ${insert.body()}")
            val select = query("SELECT x FROM t")
            assertEquals(200, select.statusCode(), "SELECT failed: ${select.body()}")
            assertEquals("1", select.body().trim())
        } finally { ch.stop() }
    }

    private fun basicAuth(user: String, pass: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
}
