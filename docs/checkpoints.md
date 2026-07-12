# Checkpoint / Restore

`checkpoint()` captures a running container's filesystem as a new image; `fromCheckpoint`
boots an ordinary container from it. The headline use case is fixture reuse across a test
suite: boot a database once, migrate and seed it, checkpoint — then restore that seeded state
per test instead of re-running migrations every time.

## Be honest about what this is

A checkpoint is a **filesystem** capture, not a memory snapshot:

- Everything written to disk before the checkpoint — migrated schema, seeded rows, files a
  process wrote — is there when you restore.
- **Processes restart from scratch.** A restored container runs the image's normal
  entrypoint/command again from the beginning; it does not resume mid-execution, and no
  in-memory state (open connections, caches, buffered writes not yet flushed to disk) survives.
- Restoring is a normal, fresh container boot against a different image — the same startup
  cost as any other container, just skipping whatever setup work is already baked into the
  filesystem.

True microVM memory snapshots — resuming a process mid-execution — need upstream microsandbox
support and stay on the [roadmap](roadmap.md).

## Capability

`capabilities.checkpoint` is `true` on the docker backend (implemented via image commit) and
`false` on microsandbox (no upstream snapshot primitive yet). `checkpoint()` checks it before
any backend call:

```kotlin
val backend = Backends.active()
backend.capabilities.checkpoint   // docker: true, microsandbox: false
```

## API

```kotlin
val db = GenericContainer("postgres:16-alpine")
    .withExposedPorts(5432)
    .waitingFor(Wait.forLogMessage(".*ready to accept connections.*"))
db.start()
db.execInContainer("psql", "-U", "postgres", "-f", "/schema.sql")   // migrate + seed

val cp = db.checkpoint()   // { imageRef: "rightsize/checkpoint:<12 hex>", spec }
db.stop()

val restored = GenericContainer.fromCheckpoint(cp)
    .waitingFor(Wait.forLogMessage(".*ready to accept connections.*"))
restored.start()   // fresh container, migrated schema and seed rows already on disk
```

`checkpoint()`:

- only works on a **running** container — a never-started or already-stopped one throws
  `IllegalStateException`;
- on an unsupported backend (microsandbox today), throws `CheckpointUnsupportedException`
  before any backend call is made;
- returns a `Checkpoint`: `imageRef` (`rightsize/checkpoint:<12-hex>`, a random tag minted per
  call — two checkpoints of the same container never collide) and `spec` (the source
  container's env, command, exposed ports, and memory limit).

`GenericContainer.fromCheckpoint(cp)`:

- builds a normal container whose image is `cp.imageRef` and whose env/command/exposed
  ports/memory limit default to `cp.spec` — apply the usual `withX` builders afterward to
  override any of them (a different wait strategy, extra env, more exposed ports) before
  calling `start()`;
- returns an **ordinary** container in every other respect: fresh host ports, a normal reaping
  ledger entry, normal `stop()`. Nothing about a restored container is special once it's
  running — it's indistinguishable from a container booted any other way.

## The seeded-fixture pattern

The pattern this feature exists for: checkpoint once per test suite, restore once per test.

```kotlin
class OrderRepositoryTest {
    companion object {
        private lateinit var checkpoint: Checkpoint

        @JvmStatic
        @BeforeAll
        fun seedOnce() {
            val db = GenericContainer("postgres:16-alpine")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*ready to accept connections.*"))
            db.start()
            db.execInContainer("psql", "-U", "postgres", "-f", "/schema.sql")
            db.execInContainer("psql", "-U", "postgres", "-f", "/seed.sql")
            checkpoint = db.checkpoint()
            db.stop()
        }
    }

    private lateinit var db: GenericContainer<*>

    @BeforeEach
    fun restore() {
        db = GenericContainer.fromCheckpoint(checkpoint)
            .waitingFor(Wait.forLogMessage(".*ready to accept connections.*"))
        db.start()
    }

    @AfterEach
    fun teardown() = db.stop()

    // each test gets its own restored copy of the migrated + seeded database, without
    // re-running migrations or seed scripts, and without one test's writes leaking into another
}
```

Every test restores from the same `imageRef`, so each gets an independent copy of the seeded
state — writes in one test never leak into another, and there's no need to reset/truncate
tables between tests.

## The microsandbox story

`checkpoint()` on microsandbox throws `CheckpointUnsupportedException` naming the docker
backend as the remedy:

```text
checkpoint() requires a backend with checkpoint support, but the active backend is
'microsandbox', which does not support it — set RIGHTSIZE_BACKEND=docker to use the docker
backend, which implements checkpoint via image commit (native microVM memory snapshots for
microsandbox are on the roadmap)
```

There is no filesystem-only fallback on microsandbox today — the seeded-fixture pattern above
requires `RIGHTSIZE_BACKEND=docker` for the checkpointing test run. A future microsandbox
release with a native VM snapshot primitive would let it implement `capabilities.checkpoint`
too — see the [roadmap](roadmap.md).

## Image cleanup

Checkpoint images are not auto-reaped — they're images, not containers, so none of
[Orphan Reaping](reaping.md)'s ledger/sweep/watchdog machinery touches them. Clean them up
explicitly:

```sh
docker rmi $(docker images -q rightsize/checkpoint)
```
