# Checkpoint / Restore

`checkpoint()` captures a running container's filesystem; `fromCheckpoint` boots an ordinary
container from it. The headline use case is fixture reuse across a test suite: boot a database
once, migrate and seed it, checkpoint — then restore that seeded state per test instead of
re-running migrations every time.

## Be honest about what this is

A checkpoint is a **filesystem** capture, not a memory snapshot:

- Everything written to disk before the checkpoint — migrated schema, seeded rows, files a
  process wrote — is there when you restore.
- **Processes restart from scratch.** A restored container runs the image's normal
  entrypoint/command again from the beginning; it does not resume mid-execution, and no
  in-memory state (open connections, caches, buffered writes not yet flushed to disk) survives.
  If you have just written files via `execInContainer`, run `sync` in the guest before
  checkpointing — an unflushed write is exactly the kind of in-memory state a checkpoint
  does not capture.
- **RAM-backed mounts are not on disk at all.** The microsandbox guest mounts `/tmp` as tmpfs,
  so files written there never enter a checkpoint — write anything you need restored to a
  rootfs path such as `/srv` or `/var`.
- Restoring is a normal, fresh container boot against a checkpoint reference — the same startup
  cost as any other container, just skipping whatever setup work is already baked into the
  filesystem.

True microVM memory snapshots — resuming a process mid-execution — need upstream microsandbox
support and stay on the [roadmap](roadmap.md).

## Mechanism per backend

Both backends support checkpoint/restore today, via different mechanisms:

| | Docker | microsandbox |
|---|---|---|
| Mechanism | Commit the running container to a new image | Stop the sandbox, snapshot its disk, and boot it back from that snapshot under the same name and ports |
| Source container afterward | Undisturbed — never stopped | Briefly stopped, then running again |
| Workload | Never restarts | Restarts (the VM reboots) |
| `capabilities.checkpointRestartsWorkload` | `false` | `true` |
| `Checkpoint.ref` shape | `rightsize/checkpoint:<12-hex>` (an image tag) | `rz-ckpt-<12-hex>` (a snapshot name) |

microsandbox's `msb snapshot create` requires the sandbox stopped, so `checkpoint()` there runs
`msb stop` → `msb snapshot create --from <sandbox> <ref>` → `msb rm <sandbox>` → a fresh attached
`msb run --snapshot <ref>` under the same name, ports, env, and memory limit — the sandbox ends up
running again under the same name, but its workload command re-ran from scratch to get there.
Because of that, `checkpoint()` re-applies the container's own wait strategy before returning
whenever the active backend's `capabilities.checkpointRestartsWorkload` is `true` — a bare return
would otherwise hand back a container that looks ready but whose workload hasn't actually come
back up yet (e.g. msb's loopback forwarder accepts TCP before the guest listens). Docker's
checkpoint never restarts anything, so no re-wait happens there. Either way this is transparent to
callers: `checkpoint()` only returns once the source container is genuinely ready again.

If the microsandbox snapshot step itself fails, the sandbox is left stopped and `checkpoint()`
throws naming the failed step — resume it by hand with `msb start <sandbox>` — rather than
best-effort restarting it, since the disk snapshot is unaffected by the sandbox's own state. If
the snapshot succeeds but the re-boot from it fails (after the stopped sandbox has already been
removed), `checkpoint()` throws naming the checkpoint ref: the original sandbox is gone, but its
state is still recoverable via `GenericContainer.fromCheckpoint`.

The reboot also kills microsandbox's emulated network links (the exec-tunneled `installNetworkLinks`
connections a container on a `Network` had at start), so `checkpoint()` re-establishes them against
the same sandbox before returning, right alongside the wait-strategy re-run.

## Capability

`capabilities.checkpoint` is `true` on both real backends:

```kotlin
val backend = Backends.active()
backend.capabilities.checkpoint                  // true on both docker and microsandbox
backend.capabilities.checkpointRestartsWorkload   // docker: false, microsandbox: true
```

`checkpoint()` checks `capabilities.checkpoint` before any backend call — this only fires for a
backend that doesn't declare the capability at all (e.g. a test double), not for either real
backend.

## API

```kotlin
val db = GenericContainer("postgres:16-alpine")
    .withExposedPorts(5432)
    .waitingFor(Wait.forLogMessage(".*ready to accept connections.*"))
db.start()
db.execInContainer("psql", "-U", "postgres", "-f", "/schema.sql")   // migrate + seed

val cp = db.checkpoint()   // { ref: "rightsize/checkpoint:<12 hex>" or "rz-ckpt-<12 hex>", backend, spec }
db.stop()

val restored = GenericContainer.fromCheckpoint(cp)
    .waitingFor(Wait.forLogMessage(".*ready to accept connections.*"))
restored.start()   // fresh container, migrated schema and seed rows already on disk
```

`checkpoint()`:

- only works on a **running** container — a never-started or already-stopped one throws
  `IllegalStateException`;
- on an unsupported backend, throws `CheckpointUnsupportedException` before any backend call is
  made — naming the active backend and pointing at `capabilities.checkpoint`;
- returns a `Checkpoint`: `ref` (backend-shaped, random per call — two checkpoints of the same
  container never collide), `backend` (the backend that created it — `"docker"`/`"microsandbox"`,
  matching `RIGHTSIZE_BACKEND`), and `spec` (the source container's env, command, exposed ports,
  and memory limit);
- optionally takes a `name` (`checkpoint("seeded-db")`) to make the checkpoint durable and
  rediscoverable from any later process, instead of living only in the returned `Checkpoint` — see
  [Reusing checkpoints across runs](#reusing-checkpoints-across-runs) below.

`GenericContainer.fromCheckpoint(cp)`:

- builds a normal container whose env/command/exposed ports/memory limit default to `cp.spec` —
  apply the usual `withX` builders afterward to override any of them (a different wait strategy,
  extra env, more exposed ports) before calling `start()`;
- requires the active backend at `start()` time to match `cp.backend` — restoring an msb
  snapshot under the docker backend (or vice versa) throws `CheckpointBackendMismatchException`
  before any backend call, naming both backends and the `RIGHTSIZE_BACKEND=<creator>` remedy;
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

Every test restores from the same `ref`, so each gets an independent copy of the seeded state —
writes in one test never leak into another, and there's no need to reset/truncate tables between
tests.

## Restoring under the wrong backend

A checkpoint's `ref` is only meaningful to the backend that created it — a docker image tag means
nothing to msb, and an msb snapshot name means nothing to docker. Restoring under a different one
fails fast:

```text
This checkpoint was created by the 'microsandbox' backend, but the active backend is 'docker' —
set RIGHTSIZE_BACKEND=microsandbox to restore it, or call checkpoint() again under 'docker' to
create one it can restore
```

## Reusing checkpoints across runs

Everything above makes a checkpoint work like a pause button: the `Checkpoint` object
`checkpoint()` returns is the only way to reach it, and it only exists for as long as this
process keeps it around. Passing a **name** to `checkpoint()` instead makes the checkpoint
durable and rediscoverable from any later process — the headline use case is a checkpoint seeded
once (in CI setup, or by hand) and restored by every later test run without ever re-running the
seed step in-process:

```kotlin
val db = Checkpoint.find("seeded-db") ?: run {
    val seed = GenericContainer("postgres:16-alpine")
        .withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*ready to accept connections.*"))
    seed.start()
    seed.execInContainer("psql", "-U", "postgres", "-f", "/schema.sql")
    val cp = seed.checkpoint("seeded-db")
    seed.stop()
    cp
}

val restored = GenericContainer.fromCheckpoint(db)
    .waitingFor(Wait.forLogMessage(".*ready to accept connections.*"))
restored.start()   // migrated schema already on disk, whether this is the first run or the hundredth
```

The first run takes the `?: run { ... }` branch and seeds a fresh checkpoint; every later run —
in the same process or a brand new one — finds it via `Checkpoint.find` and skips straight to
`fromCheckpoint`.

### Names and refs

A name must match `^[a-z0-9][a-z0-9-]{0,40}$` — lowercase letters, digits, and hyphens, starting
with a letter or digit, up to 41 characters. An invalid name throws `InvalidCheckpointNameException`
before any backend call, the same fail-fast placement every other checkpoint precondition uses.

`checkpoint(name)`'s ref is derived from the name instead of being random: `rightsize/checkpoint:<name>`
for docker, `rz-ckpt-<name>` for microsandbox — the same shapes an unnamed `checkpoint()` uses,
just with the name in place of the random 12-hex suffix.

### Replace semantics

Calling `checkpoint(name)` again for a name that already has a checkpoint **replaces** it: the old
backend artifact is best-effort removed first (only when that old entry's backend matches the
currently active one; see below), then the new one is captured, and only once that capture
succeeds is the registry entry rewritten to point at it — latest wins. A failed capture (the
backend call itself throws) leaves the previous registry entry untouched; a *successful* capture
that races a concurrent `checkpoint(name)` call for the same name has no defined winner beyond
"whichever registry write lands last."

### The registry

A named checkpoint's metadata lives in one JSON file per name, under the rightsize cache
directory: `<cache-dir>/checkpoints/<name>.json`, written atomically (tmp file + rename) only
after the backend checkpoint itself succeeds. The shape — pinned identically across the three
rightsize libraries — is:

```json
{
  "name": "seeded-db",
  "ref": "rightsize/checkpoint:seeded-db",
  "backend": "docker",
  "createdIso": "2026-07-11T12:00:00Z",
  "spec": {
    "env": {},
    "command": null,
    "exposedPorts": [5432],
    "memoryLimitMb": null
  }
}
```

`Checkpoint.find(name)`, `Checkpoint.list()`, and `Checkpoint.remove(name)` are the only supported
way to read or write this file — treat it as an implementation detail, not a stable format to
hand-edit.

### find / list / remove

- **`Checkpoint.find(name): Checkpoint?`** — `null` if there's no entry for `name`. If the entry's
  `backend` matches the currently active backend, its artifact is probed
  (`hasCheckpoint`): gone means the entry is stale, so it's cleaned up and `find` returns `null`
  just like the no-entry case. An entry recorded under a **different** backend than the active one
  is returned **without** probing — restoring it still goes through `fromCheckpoint`'s own
  `CheckpointBackendMismatchException` gate, so `find` never has to force-resolve a backend the
  host may not even have. A corrupt registry file is treated the same as no entry, with the bad
  file best-effort removed.
- **`Checkpoint.list(): List<Checkpoint>`** — every entry currently in the registry, no artifact
  probing at all. A stale entry (backend-side artifact already gone) can still show up here; only
  `find`/`remove` resolve staleness. A corrupt entry is silently skipped.
- **`Checkpoint.remove(name): Boolean`** — best-effort removes the backend artifact (only when the
  entry's `backend` matches the currently active one, same gate as `find`) and the registry entry;
  returns `true` only if an entry actually existed. Idempotent: calling it again on an
  already-removed (or never-existing) name is always safe and returns `false`. Removing (or
  replacing) a checkpoint under a different active backend than its creator drops the registry
  record but leaves the artifact behind, and once the record is gone a later `remove` finds
  nothing to act on — remove a checkpoint under its creating backend in the first place, or use the
  [manual CLI cleanup](#cleanup) below.

## Cleanup

Checkpoints are not auto-reaped — a docker checkpoint is an image, an msb checkpoint is a
snapshot artifact, and neither is a container, so none of [Orphan Reaping](reaping.md)'s
ledger/sweep/watchdog machinery touches either. For a named checkpoint, `Checkpoint.remove(name)`
is the affordance for that — it works from any process, not just the one that created it. The
manual CLI one-liners below are still valid (useful for an unnamed checkpoint, or when you'd
rather not go through the API at all), just no longer the only way to clean one up:

```sh
# docker
docker rmi rightsize/checkpoint:<12-hex-or-name>

# microsandbox
msb snapshot rm rz-ckpt-<12-hex-or-name>
```
