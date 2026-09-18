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
| Mechanism | Commit the running container to a new image | Stop the sandbox, snapshot its disk, and boot it back from that snapshot under a freshly generated sandbox name, keeping the same ports |
| Source container afterward | Undisturbed — never stopped | Briefly stopped, then running again |
| Workload | Never restarts | Restarts (the VM reboots) |
| `capabilities.checkpointRestartsWorkload` | `false` | `true` |
| `Checkpoint.ref` shape | `rightsize/checkpoint:<12-hex>` (an image tag) | an absolute path under `<cache-dir>/checkpoints/`, one directory level deeper than the dest-dir/name this library asks for (see below) |

microsandbox's `msb snapshot create` requires the sandbox stopped, so `checkpoint()` there runs
`msb stop` → `msb snapshot create --from-sandbox <sandbox> <name> --dest-dir <cache-dir>/checkpoints`
→ `msb rm <sandbox>` → `msb restore <ref> --name <freshName>` — a FRESHLY GENERATED sandbox name
(`rz-<runId>-<n>`, the exact same generator/counter an ordinary boot already uses, not a second
naming scheme), never the original sandbox's own name. Ports/memory limit carry over unchanged;
the workload command re-ran from scratch to get there either way. The live handle's `id`/`spec`
are rewritten to the fresh name in place before the reboot is attempted, so every subsequent call
against it — `exec`/`logs`/`stop`/`removeCheckpoint`'s later use — keeps working transparently
without the caller ever seeing or needing to know the new name.

**Why not the same name — and why never the same RETRY name either.** `msb rm` on Windows can
return before the sandbox's on-disk directory is actually released — msb's own existence check for
a restore target is "DB record present OR directory present", and the directory has been observed
on Windows CI outliving the DB record by multiple seconds under load. A same-name restore issued
into that gap fails outright with msb's own "already exists" error, for as long as the directory
lingers — not a fixed bound, so no retry budget can be sized to reliably outlast it. A restore
under a freshly generated name never collides with the just-removed sandbox's own lingering
directory at all, sidestepping the whole lag rather than racing it. The sandbox's name across a
checkpoint was always an implementation detail, never a documented contract — nothing in this
library's public API names it. (The post-`rm` `msb ls` name-release wait below predates this fix
and still runs, as harmless, now largely dormant defense in depth — a freshly generated name
colliding with some other still-live sandbox is vanishingly unlikely, unlike the near-certainty a
same-name restore risked on a loaded Windows host.)

A fresh name is not immune to the same lag family, though: `msb restore <name>` validates the
snapshot artifact first (an integrity failure exits 1 with no sandbox record left at all), but a
failure AFTER validation — most often Windows' deferred-file-release access-denied error — can
still leave that fresh name itself behind as a stopped sandbox record, and a retry under the SAME
name then collides with its own leftover exactly as a same-name restore of the original name would
have. The reboot's retry loop therefore never reuses a name either: on msb's already-exists refusal
or the access-denied signature, it best-effort removes the just-failed name and mints ANOTHER fresh
name from the same generator before retrying, bounded by the same ~30-second wall-clock budget (not
an attempt count) the retry has always used — so the retry outlives the lag across as many distinct
names as it takes, rather than exhausting its budget colliding with one.

As of msb 0.7.1, `--from-sandbox` always writes a DISK-scope snapshot, and restoring
one is inherently a cold boot of the captured disk alone (no resumed RAM/processes, matching this
library's filesystem-only checkpoint semantics) — with **no** `--disk-only` flag: msb 0.7.1
REJECTS that flag outright for a disk-scope source (`invalid config: disk_only requires a full
snapshot with checkpoint state`; an earlier msb pin of this library did pass it). Env and the boot
command are never re-passed either way (`msb restore` has no `-e`/`--env` flag and no
trailing-command flag at all, unlike `msb run`) — restore replays the sandbox's own captured
configuration instead, which for this same-container reboot is exactly what was already running a
moment earlier.

The reaper's run ledger (see [reaping.md](reaping.md)) tracks EVERY name the reboot mints the same
way it tracks an ordinary create — each one appended before its own restore attempt, including a
retry's fresh name after a refused one — and simply leaves the old (pre-checkpoint) name's own
entry, and any refused attempt's own entry, for its existing not-found-tolerant sweep to pick up,
since the sandbox under each of those names is already gone (or best-effort removed) by then. A
successful cycle therefore adds exactly ONE new entry to the ledger beyond what was there before —
the winning name — not zero: this is a deliberate change from this library's earlier,
pre-fresh-name checkpoint behavior, where the reboot reused the source sandbox's own name and so
never touched the ledger at all.

`msb restore` is **not** supervised the way `msb run` is. Per upstream's own doc, `restore` boots
a new *detached* sandbox: the `restore` process activates it and exits — typically within seconds,
often with no output — while the sandbox keeps booting toward `Running` in the background; a
nonzero exit means the restore itself failed. This library waits for that process to exit
(classifying a nonzero exit the same transient-failure-aware way an ordinary boot's early exit
is), then polls `msb ls` for `Running` separately, on the same budget an ordinary attached boot
polls on. The resulting sandbox handle has no supervising child process to reap, kill, or watch
for an early death — `stop()`/`exec()`/`logs()` all already operate through the CLI by name and
are unaffected.

**`ref` is not what this library asks msb to write to.** `--dest-dir` only tells msb where to
root the artifact — under the rightsize cache directory instead of its own default
`~/.microsandbox/snapshots/` — and `<name>` only ever shows up in msb's own index (as
`<sandbox>:<name>` in `snapshot list`) and in `snapshot inspect` output. The actual artifact
lands at `<dest-dir>/<sandbox>/snap_<32-hex digest>`, a path msb decides on its own — NOT at
`<dest-dir>/<name>`. `checkpoint()` therefore captures the real artifact path from `snapshot
create`'s own stdout (msb prints the snapshot ID, then the absolute artifact path, as its last
line on success) and uses THAT as `Checkpoint.ref`, never the dest-dir/name hint it asked for.
The snapshot still shows up in `msb snapshot list` — msb keeps a global index regardless of where
the artifact physically lives — so `Checkpoint.remove` cleans both the index entry and the
on-disk artifact. Unlike earlier msb releases, a BARE name (or a `group:member` spec) does not
reliably resolve for `snapshot rm`/`snapshot inspect` as of 0.7.1 — the artifact's own path is
the one address that reliably works for everything, which is exactly what every ref this library
mints or captures now is.

A checkpoint of a `withTmpfsRoot()` container is refused up front: `checkpoint()` throws
`TmpfsRootCheckpointException` before `msb stop` even runs, since a tmpfs root lives in guest
memory and there is nothing durable on disk to snapshot — this applies whether the call is an
unnamed `checkpoint()` or a named `checkpoint("existing-name")`, so a refused named
re-checkpoint leaves the existing checkpoint entirely untouched. Restoring the other direction has
a matching constraint: `msb restore` has no root-disk or network-policy flag at all, because the
snapshot already pins the disk/network geometry — the same reasoning that keeps this restore
command from taking an env or command override either. Calling `withDiskLimit`/`withTmpfsRoot`/
`withNetworkDisabled` on a `fromCheckpoint` restore throws `CheckpointRestoreOverrideUnsupportedException`
at `start()`, the same exception and the same guard env/command overrides trip (see the "API"
section below) — none of the three is ever part of what a checkpoint captures, so using any of
them after `fromCheckpoint` is always a genuine divergence, never a re-statement of a captured
value.
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

val cp = db.checkpoint()   // { ref: "rightsize/checkpoint:<12 hex>" or "<cache-dir>/checkpoints/<sandbox>/snap_<hex>", backend, spec }
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
  apply the usual `withX` builders afterward (a different wait strategy, more exposed ports, a
  higher memory limit) before calling `start()`;
- overriding env or command specifically (`withEnv`/`withCommand`/`removeEnv`, to something other
  than what `cp.spec` already carries) works on a backend whose
  `capabilities.checkpointRestoreOverridable` is `true` (Docker: restoring just re-runs the
  committed image with the new env/command, same as any other `docker run`) but throws
  `CheckpointRestoreOverrideUnsupportedException` before any backend call on one where it's
  `false` (microsandbox: `msb restore` of a disk-scope snapshot has no `-e`/`--env` flag and no
  command-override flag at all — it always replays the snapshot's own captured configuration, so
  a caller's override would otherwise be silently dropped rather than genuinely applied);
- calling `withDiskLimit`/`withTmpfsRoot`/`withNetworkDisabled` at all, for any value, after
  `fromCheckpoint` is gated the same way: fine on a backend whose
  `capabilities.checkpointRestoreOverridable` is `true`, but throws
  `CheckpointRestoreOverrideUnsupportedException` before any backend call on microsandbox — none
  of the three is ever part of `cp.spec` (a checkpoint never captures disk/network geometry), so
  unlike env/command there is no "re-supplying the same captured value" case that's allowed
  through; any use of them after `fromCheckpoint` on microsandbox is rejected;
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

`checkpoint(name)`'s ref is derived from the name instead of being random on docker
(`rightsize/checkpoint:<name>`, the ref itself, verbatim). On microsandbox, `<name>` is only ever
a HINT (`<cache-dir>/checkpoints/rz-ckpt-<name>`, in place of the random 12-hex suffix an unnamed
`checkpoint()` uses) telling `msb snapshot create` which dest-dir/snapshot-name to use — the
actual `ref` msb hands back is one directory level deeper, under a directory msb itself names
after the source sandbox (see [Mechanism per backend](#mechanism-per-backend) above). Either way,
`ref` is an opaque string — nothing downstream parses it, only `fromCheckpoint`/`remove`/the
manual CLI one-liners consume it, so treat the shape as informational rather than something to
pattern-match.

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

# microsandbox — the full artifact path, not just the snapshot name
msb snapshot rm <cache-dir>/checkpoints/<source-sandbox>/snap_<hex-digest> -f
```

`msb snapshot rm` (as of msb 0.7.1) resolves reliably only against the snapshot's own artifact
path — a bare name or `group:member` spec does not resolve — so pass `Checkpoint.ref` itself,
`-f` included to skip msb's own confirmation prompt. This is exactly what
`Checkpoint.remove`/`removeCheckpoint` do internally, plus a best-effort delete of the artifact
directory if msb's own removal doesn't already take it with it.

**Known limitation: removing the head of several checkpoints from the same source sandbox.** msb
refuses to remove the NEWEST (head) snapshot created from a given source sandbox while an OLDER
sibling from the same source still exists (`invalid config: cannot remove current head
snap_...; first select another snapshot with 'msb snapshot head src:<snapshot>'`) — removing a
non-head member, or a group's only member, works normally. `Checkpoint.remove`/`removeCheckpoint`
are best-effort and never inspect msb's exit code, so this refusal is silently absorbed: the
call returns normally and the snapshot is simply left in place, neither retried nor worked around
with automatic head rotation. If you checkpoint the same source sandbox by name repeatedly and
need reliable removal, either remove older siblings first (oldest to newest) or run `msb snapshot
head src:<snapshot>` by hand to pick a different head before removing the one you actually want
gone.

## Moving checkpoints between machines

Everything above rediscovers a checkpoint on the SAME machine that created it — the registry
entry points at a local docker image or a local msb snapshot, neither of which travels on its
own. `exportTo`/`importFrom` package a checkpoint into a single portable archive file, so it can
ride along in a CI cache, an artifact store, or a plain `scp`, instead of being re-seeded from
scratch on every runner:

```kotlin
val cp = db.checkpoint("seeded-db")
cp.exportTo(Path.of("seeded-db.tar"))   // a plain tar: pinned metadata + the backend's own payload

// ...on this machine later, or on another one running the SAME backend:
val restored = Checkpoint.importFrom(Path.of("seeded-db.tar"))
GenericContainer.fromCheckpoint(restored).start()
```

The CI-cache pattern this exists for: seed and `checkpoint("seeded-db")` once, `exportTo` the
archive, cache the archive file across runs — every later run skips the seed step entirely by
`importFrom`-ing the cached archive instead of re-seeding from scratch. `Checkpoint.find("seeded-db")`
still works as the first line of defense (same machine, same process history); `importFrom` is
what covers the cache-miss/fresh-runner case.

### The archive format

A plain tar with exactly two members at its root — no container-level compression (msb's payload
is already zstd-compressed; a docker `save` tar compresses poorly enough not to matter here):

- `checkpoint.json` — the same pinned shape as a [named checkpoint's registry entry](#the-registry),
  plus a format version and a nullable `name` (an exported UNNAMED checkpoint carries `name: null`).
- `artifact` — the backend's own payload file, byte-for-byte what the backend CLI produced: an
  msb `snapshot save` `.tar.zst`, or a docker `save` tar.

### Not bundled: the OCI image

`exportTo` never bundles the container image itself, on either backend — a docker archive is the
committed layer(s) on top of the base image, not the base image; an msb archive is the disk
snapshot artifact alone (`msb snapshot save` deliberately never runs with `--with-image`, whose
import fails an integrity check on msb 0.6.6). The destination machine pulls the image normally on
the restored container's first boot, exactly as it would for any other container using that image
— make sure it's reachable there (a private registry needs the same credentials it would for a
fresh pull).

### API

- **`Checkpoint.exportTo(path)`** — requires the ACTIVE backend to equal the checkpoint's own
  `backend`, or throws `CheckpointBackendMismatchException` before any backend or filesystem work
  (the same typed mismatch `fromCheckpoint`/`checkpoint(name)`'s replace path already use).
  Requires the backend artifact to still exist (`SandboxBackend.hasCheckpoint`), or throws a typed
  error rather than writing a broken archive. `path`'s parent directories are created if missing;
  an existing file there is overwritten.
- **`Checkpoint.importFrom(path)`** — validates `checkpoint.json` (format version, `name` against
  the checkpoint-name grammar when present, and its recorded `backend` against the active one)
  entirely before any backend call; a missing file, a malformed archive, or a wrong-backend one
  throws a typed error. On success, returns a `Checkpoint` carrying the backend's EFFECTIVE ref —
  see below — and, for a NAMED archive, re-registers it under that name with the same replace
  semantics `checkpoint(name)` uses (the old same-backend artifact is best-effort removed first).
  A nameless archive returns an ephemeral `Checkpoint` with no registry write. Either way, the
  returned `Checkpoint` restores via `GenericContainer.fromCheckpoint` exactly like any other —
  refs are opaque, and nothing downstream cares how one was minted.

### The effective ref: docker round-trips the tag, msb mints its own artifact path under the same checkpoints dir

The ref a checkpoint restores by is not always the archived one:

| | docker | microsandbox |
|---|---|---|
| Import mechanism | `docker load -i <archive>` | `msb snapshot load <archive> --dest <cache-dir>/checkpoints` |
| Effective ref after import | The original tag, unchanged (`Loaded image: <tag>`) | The LOADED ARTIFACT's own absolute path — the original snapshot name is never preserved |

An imported msb checkpoint therefore shows up in `Checkpoint.find`/`list` with an absolute
`<cache-dir>/checkpoints/<msb-group>/snap_<hex>` path — the same `<cache-dir>/checkpoints`
directory a locally created checkpoint's `--dest-dir` writes under (see above), just with msb's
own choice of group/snapshot naming nested beneath it, since `--dest` is always passed rather
than left to default to msb's own `~/.microsandbox/snapshots/` store. `snapshot load` prints that
full path as the LAST line of its stdout on an ordinary success; `fromCheckpoint`, `snapshot rm`,
and `snapshot inspect` all take it verbatim. Importing an archive whose content already exists on
the destination is itself a success, not an error — the artifact is already there. That outcome's
own stdout shape on msb 0.7.1 isn't independently confirmed, so ref parsing tries stdout first and
falls back to stderr (the shape verified against msb 0.6.8) before giving up.

### Archive size expectations

msb archives are the zstd-compressed sparse disk snapshot — small for a lightly modified image (a
tiny alpine snapshot's on-disk artifact was observed at a few MB despite a multi-GiB nominal disk
size). docker archives are a full `docker save`, proportional to the image's own size plus
whatever layers the checkpoint committed on top.
