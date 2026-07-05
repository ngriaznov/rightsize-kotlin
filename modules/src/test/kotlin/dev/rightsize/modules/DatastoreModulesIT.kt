package dev.rightsize.modules

import com.arangodb.ArangoDB
import com.mongodb.client.MongoClients
import io.lettuce.core.RedisClient
import org.bson.Document
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.sql.DriverManager

@Tag("sandbox-it")
class DatastoreModulesIT {
    @Test fun redis() {
        val redis = RedisContainer(); redis.start()
        try {
            RedisClient.create(redis.uri).connect().use { conn ->
                conn.sync().set("k", "v"); assertEquals("v", conn.sync().get("k"))
            }
        } finally { redis.stop() }
    }

    @Test fun arango() {
        val arango = ArangoContainer(); arango.start()
        try {
            val db = ArangoDB.Builder().host("127.0.0.1", arango.getMappedPort(8529)).build()
            assertNotNull(db.version.version); db.shutdown()
        } finally { arango.stop() }
    }

    @Test fun memcached() {
        val mc = MemcachedContainer(); mc.start()
        try {
            java.net.Socket("127.0.0.1", mc.getMappedPort(11211)).use { s ->
                s.getOutputStream().write("version\r\n".toByteArray())
                assertTrue(s.getInputStream().bufferedReader().readLine().startsWith("VERSION"))
            }
        } finally { mc.stop() }
    }

    @Test fun mongoReplicaSet() {
        val mongo = MongoDBContainer(); mongo.start()
        try {
            MongoClients.create(mongo.connectionString).use { client ->
                val col = client.getDatabase("test").getCollection("t")
                col.insertOne(Document("x", 1))
                assertEquals(1, col.countDocuments())
            }
        } finally { mongo.stop() }
    }

    @Test fun postgres() {
        val postgres = PostgreSQLContainer(); postgres.start()
        try {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE t (x INT)") }
                conn.createStatement().use { it.execute("INSERT INTO t (x) VALUES (1)") }
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT x FROM t").use { rs ->
                        assertTrue(rs.next()); assertEquals(1, rs.getInt("x"))
                    }
                }
            }
        } finally { postgres.stop() }
    }

    @Test fun mysql() {
        val mysql = MySQLContainer(); mysql.start()
        try {
            DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE t (x INT)") }
                conn.createStatement().use { it.execute("INSERT INTO t (x) VALUES (1)") }
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT x FROM t").use { rs ->
                        assertTrue(rs.next()); assertEquals(1, rs.getInt("x"))
                    }
                }
            }
        } finally { mysql.stop() }
    }
}
