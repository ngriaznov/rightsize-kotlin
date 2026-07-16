# Roadmap

Ideas under consideration for future releases, roughly ordered by expected impact.
Items graduate off this page when they ship; the CHANGELOG records what landed.

## Native microVM memory snapshots

Filesystem-level checkpoint/restore has shipped on both backends — docker via
image commit, microsandbox via disk snapshot (see
[Checkpoint / Restore](checkpoints.md)). What's left: true microVM *memory*
snapshots on microsandbox, resuming a process mid-execution instead of
restarting it from a captured filesystem. That needs a memory-snapshot
primitive upstream in microsandbox itself, beyond the disk-snapshot one it
has today; once available, it would also make restore near-instant.

## Portable checkpoint archives

Named checkpoints (see [Checkpoint / Restore](checkpoints.md)) are rediscoverable across
processes on the SAME machine, but the artifact itself — a docker image, an msb snapshot — never
leaves it. Export/import (`docker save`/`docker load`, `msb snapshot export`/an equivalent
import) would let a checkpoint travel between machines or ride along in a CI cache, rather than
being re-seeded from scratch on every runner.

## Module breadth

The gaps Testcontainers users will hit first: LocalStack, Elasticsearch /
OpenSearch, Vault, MinIO, NATS, Cassandra, MSSQL, Oracle Free, and Ollama
(LLM-in-a-box testing, which also fits the isolation story).

## Framework integrations

One-annotation setup in the frameworks people actually use: Spring Boot
`@ServiceConnection`-style wiring, Quarkus Dev Services, a pytest-style
fixture story, Vitest/Jest global-setup helpers, Axum/sqlx examples.

## Building images from code

Define an ad-hoc image inline in the test (Dockerfile-from-code) instead of
publishing one — for testing your own service, not just its dependencies.

## Host-directory mounts

Runtime copy in both directions has shipped — see [Copying Files](copy.md).
What's left: host-directory binds alongside the existing single-file
`withCopyFileToContainer` mount, for start-time cases that want a live
host-directory view rather than a one-time copy.

## Declarative multi-service groups

A rightsize-native way to declare "these five services, this network, this
startup order" as one artifact, serving the docker-compose need without the
compose file format.

## Warm pools

A background pool of pre-booted sandboxes so `start()` is near-instant —
paired with reuse, this attacks time-to-first-test directly.

## Fault injection

The backend controls the virtual NIC: latency, packet loss, partitions
between sandboxes, kill-and-revive — first-class API instead of a separate
Toxiproxy container.

## Time control

A VM owns its clock: advance time inside the guest to test TTLs, certificate
expiry, and cron logic faithfully — awkward to impossible on a shared
kernel.

## Private registry authentication

Pulling from authenticated registries, documented and tested — table stakes
for enterprise evaluation.
