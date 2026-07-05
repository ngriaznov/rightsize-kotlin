# MongoDB

`dev.rightsize.modules.MongoDBContainer` — a single-node MongoDB container, always
booted as a one-member replica set (required for transactions and change streams).
The replica set is initiated and a primary confirmed elected before `start()` returns,
so `connectionString` is immediately usable — you never need to handle the
not-yet-a-primary window yourself.

## Defaults

| | |
|---|---|
| Default image | `mongo:8.0` |
| Exposed port | `27017` |
| Command | `mongod --replSet docker-rs --bind_ip_all` |
| Wait strategy | `Wait.forListeningPort()`, followed by a post-start replica-set-init hook |

## Helpers

| Member | Returns |
|---|---|
| `connectionString: String` | A `mongodb://` connection string for the `test` database, with `directConnection=true` |
| `replicaSetUrl: String` | Alias for `connectionString` — the container is always a (single-node) replica set |

## Example

```kotlin
package dev.rightsize.modules

import com.mongodb.client.MongoClients
import dev.rightsize.modules.MongoDBContainer
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MongoDBContainerTest {
    @Test
    fun `inserts and counts a document`() {
        val mongo = MongoDBContainer()
        mongo.start()
        try {
            MongoClients.create(mongo.connectionString).use { client ->
                val col = client.getDatabase("test").getCollection("t")
                col.insertOne(Document("x", 1))
                assertEquals(1, col.countDocuments())
            }
        } finally {
            mongo.stop()
        }
    }
}
```

## Backend notes

After the container reports a listening port, this module runs `rs.initiate()` (via
`execInContainer`, retrying to ride out the same proxy-accepts-before-mongod-listens
race described in [Wait Strategies](../concepts/wait-strategies.md#readiness-probe-caveats)),
then polls `db.hello().isWritablePrimary` until it returns `true` — up to 60 seconds,
polling every 500ms. Only once a primary is confirmed elected does `start()` return.
This two-stage approach (raw port ready, then a domain-specific liveness check before
handing control back to your test) is the same shape `PinotContainer` uses for its
`/health`-then-schema-POST race — see [Apache Pinot](pinot.md) for that parallel case.
