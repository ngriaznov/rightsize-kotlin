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
| [`RedisContainer`](redis.md) | `redis:8.6-alpine` | `uri` |
| [`ArangoContainer`](arangodb.md) | `arangodb:3.11` | `endpoint`, `withRootPassword(...)` |
| [`MemcachedContainer`](memcached.md) | `memcached:1.6-alpine` | `address` |
| [`MongoDBContainer`](mongodb.md) | `mongo:8.0` | `connectionString`, `replicaSetUrl` (single-node replica set, auto-initiated) |
| [`PostgreSQLContainer`](postgresql.md) | `postgres:18-alpine` | `jdbcUrl`, `username`, `password`, `databaseName`, `withUsername/withPassword/withDatabase(...)` |
| [`MySQLContainer`](mysql.md) | `mysql:8.4` | `jdbcUrl`, `username`, `password`, `databaseName`, `withUsername/withPassword/withDatabase(...)` |
| [`MariaDBContainer`](mariadb.md) | `mariadb:11.4` | `jdbcUrl`, `username`, `password`, `databaseName`, `withUsername/withPassword/withDatabase(...)` |
| [`RedpandaContainer`](redpanda.md) | `docker.redpanda.com/redpandadata/redpanda:latest` | `bootstrapServers`, `schemaRegistryUrl` |
| [`KafkaContainer`](kafka.md) | `apache/kafka:4.0.0` | `bootstrapServers` (KRaft single node) |
| [`RabbitMQContainer`](rabbitmq.md) | `rabbitmq:4-management-alpine` | `amqpUrl`, `managementUrl`, `username`, `password`, `withUsername/withPassword(...)` |
| [`ClickHouseContainer`](clickhouse.md) | `clickhouse/clickhouse-server:25.8` | `httpUrl`, `username`, `password`, `databaseName`, `withUsername/withPassword/withDatabase(...)` |
| [`PinotContainer`](pinot.md) | `apachepinot/pinot:1.5.1` | `controllerUrl`, `brokerUrl` (QuickStart `-type EMPTY` single-container cluster) |
| [`SpringCloudConfigContainer`](spring-cloud-config.md) | `hyness/spring-cloud-config-server:latest` | `uri` |
| [`WireMockContainer`](wiremock.md) | `wiremock/wiremock:3.13.2` | `baseUrl`, `adminUrl` |
| [`KeycloakContainer`](keycloak.md) | `quay.io/keycloak/keycloak:26.4` | `authServerUrl`, `managementUrl`, `adminUsername`, `adminPassword`, `withAdminUsername/withAdminPassword(...)` |
| [`Neo4jContainer`](neo4j.md) | `neo4j:5-community` | `httpUrl`, `boltUrl`, `username`, `password`, `withPassword(...)` |
| [`FlociContainer`](floci.md) | `floci/floci(-az\|-gcp)` | `FlociContainer.aws()`/`.azure()`/`.gcp()`, `endpointUrl` |
| [`FlinkContainer`](flink.md) | `flink:1.20.5` | `restUrl`, `withTaskManager()` (docker only) |

Every module page includes: the default image and how to override it, the field
defaults, every helper method, a complete copy-paste JUnit 5 test, and any
backend-specific notes worth knowing before you run it.

## Don't see what you need?

Every module is a thin subclass of [`GenericContainer`](../concepts/containers.md) —
if there's no preconfigured module for your image, use `GenericContainer` directly
with your own `withEnv`/`withExposedPorts`/`waitingFor` calls. See
[Getting Started](../getting-started.md#plain-api-no-junit-extension) for the shape.
