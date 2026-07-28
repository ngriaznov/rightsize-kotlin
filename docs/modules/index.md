# Modules

The `modules` artifact ships preconfigured containers — a sensible default image, an
exposed-ports set, a wait strategy that's been checked against a real boot (not just
assumed from the docs), and connection helpers that hand you a ready-to-use URI or
JDBC URL rather than making you assemble one from `getMappedPort` calls yourself.

```kotlin
testImplementation("dev.rightsize:modules")
```

## Catalog

| Module | Default image | Helpers |
|---|---|---|
| [`RedisContainer`](redis.md) | `redis:latest` | `uri` |
| [`ValkeyContainer`](valkey.md) | `valkey/valkey:latest` | `uri` |
| [`ArangoContainer`](arangodb.md) | `arangodb:latest` | `endpoint`, `withRootPassword(...)` |
| [`MemcachedContainer`](memcached.md) | `memcached:latest` | `address` |
| [`MongoDBContainer`](mongodb.md) | `mongo:latest` | `connectionString`, `replicaSetUrl` (single-node replica set, auto-initiated) |
| [`PostgreSQLContainer`](postgresql.md) | `postgres:latest` | `jdbcUrl`, `username`, `password`, `databaseName`, `withUsername/withPassword/withDatabase(...)` |
| [`MySQLContainer`](mysql.md) | `mysql:latest` | `jdbcUrl`, `username`, `password`, `databaseName`, `withUsername/withPassword/withDatabase(...)` |
| [`MariaDBContainer`](mariadb.md) | `mariadb:latest` | `jdbcUrl`, `username`, `password`, `databaseName`, `withUsername/withPassword/withDatabase(...)` |
| [`RedpandaContainer`](redpanda.md) | `redpandadata/redpanda:latest` | `bootstrapServers`, `schemaRegistryUrl` |
| [`KafkaContainer`](kafka.md) | `apache/kafka:latest` | `bootstrapServers` (KRaft single node) |
| [`RabbitMQContainer`](rabbitmq.md) | `rabbitmq:management` (not `latest` — see the module page) | `amqpUrl`, `managementUrl`, `username`, `password`, `withUsername/withPassword(...)` |
| [`ClickHouseContainer`](clickhouse.md) | `clickhouse/clickhouse-server:latest` | `httpUrl`, `username`, `password`, `databaseName`, `withUsername/withPassword/withDatabase(...)` |
| [`PinotContainer`](pinot.md) | `apachepinot/pinot:latest` | `controllerUrl`, `brokerUrl` (QuickStart `-type EMPTY` single-container cluster) |
| [`SpringCloudConfigContainer`](spring-cloud-config.md) | `hyness/spring-cloud-config-server:latest` | `uri` |
| [`WireMockContainer`](wiremock.md) | `wiremock/wiremock:latest` | `baseUrl`, `adminUrl` |
| [`KeycloakContainer`](keycloak.md) | `quay.io/keycloak/keycloak:latest` | `authServerUrl`, `managementUrl`, `adminUsername`, `adminPassword`, `withAdminUsername/withAdminPassword(...)` |
| [`Neo4jContainer`](neo4j.md) | `neo4j:latest` | `httpUrl`, `boltUrl`, `username`, `password`, `withPassword(...)` |
| [`FlociContainer`](floci.md) | `floci/floci(-az\|-gcp):latest` | `FlociContainer.aws()`/`.azure()`/`.gcp()`, `endpointUrl` |
| [`FlinkContainer`](flink.md) | `flink:latest` | `restUrl`, `withTaskManager()` (docker only) |
| [`MinIOContainer`](minio.md) | `minio/minio:latest` | `endpointUrl`, `username`, `password`, `withUsername/withPassword(...)` |
| [`CassandraContainer`](cassandra.md) | `cassandra:latest` | `contactPoint`, `cqlPort`, `localDatacenter` |
| [`ElasticsearchContainer`](elasticsearch.md) | none — no floating tag exists; an explicit image is required | `restUrl` |
| [`QdrantContainer`](qdrant.md) | `qdrant/qdrant:latest` | `restUrl` |

Every module page includes: the default image and how to override it, the field
defaults, every helper method, a complete copy-paste JUnit 5 test, and any
backend-specific notes worth knowing before you run it.

No module in this catalog pins a version — every default above is a floating
reference that tracks upstream's own releases (Elasticsearch has none at all; see its
page). Each module's own KDoc/page states the specific version its readiness signal,
memory floor, and timing facts were verified against, and constructing with that
image explicitly pins it. Passing any explicit image checks its repository against
the one the module understands, failing fast with a typed `IncompatibleImageException`
rather than a bare wait-strategy timeout on a mismatch — see each module's own
"Compatibility checking" section, or [Core Concepts](../concepts/containers.md) for
`DockerImageName` itself.

## Don't see what you need?

Every module is a thin subclass of [`GenericContainer`](../concepts/containers.md) —
if there's no preconfigured module for your image, use `GenericContainer` directly
with your own `withEnv`/`withExposedPorts`/`waitingFor` calls. See
[Getting Started](../getting-started.md#plain-api-no-junit-extension) for the shape.
