# Container Reuse

A container marked for reuse survives `stop()` and process exit — it keeps running — and the
next equivalent container, in this process or a later one, *adopts* it instead of booting
fresh. This is the single biggest dev-loop speedup for anyone running the same fixture
container across many local test runs: skip the boot every time except the first.

## Double opt-in

Reuse only happens when **both** are true:

- the container is marked via `withReuse()`;
- the environment enables it: `RIGHTSIZE_REUSE=true` (exact string `"true"`, or `"1"`).

`withReuse()` on its own does nothing — a container marked but not enabled behaves exactly
like an ordinary ephemeral container (Testcontainers semantics), with a one-line stderr note
that reuse was requested but not enabled. This is deliberate: a fixture written with
`withReuse()` can ship in a shared module without silently leaking long-lived sandboxes in
every environment that runs it — only an environment that explicitly sets the variable opts
in.

```kotlin
val cache = GenericContainer("redis:8.6-alpine")
    .withExposedPorts(6379)
    .withReuse()
cache.start()   // adopts an existing one if RIGHTSIZE_REUSE is set and one already exists
```

## Identity

Whether two containers are "the same" for reuse purposes is decided by a hash over the
reuse-relevant subset of their configuration:

```text
{image, env (sorted by key), command, exposedPorts (sorted), memoryLimitMb,
 copies: [{guestPath, sha256(content)}] sorted by guestPath}
```

- `env` and `copies` are order-independent (sorted before hashing); `command` is not — argv
  order is itself meaningful.
- A mounted file's bytes are part of identity, not just its guest path — `withCopyFileToContainer`
  with different source content changes the hash even if `guestPath` doesn't.
- Host ports, the container name, and the network are **not** part of identity — a reuse
  container's host ports are whatever the first boot allocated (or whatever adoption finds),
  not something a caller chooses.

The hash is a SHA-256 over a canonical JSON rendering (stable key order, no whitespace) of that
structure, computed identically across rightsize's Kotlin, Rust, and Node libraries — a fixture
defined the same way hashes to the same value regardless of which language started it, so a
polyglot pipeline sharing one `RIGHTSIZE_CACHE_DIR` can hand a reuse container off between
processes written in different languages. See [Cross-Language Parity](parity.md) for the
pinned hash vector all three libraries are tested against.

The sandbox is named `rz-reuse-<first 12 hex chars of the hash>`.

## The registry

The first time a reuse container starts successfully and passes its wait strategy, its
identity, name, and mapped ports are written to `<cache-dir>/reuse/<hash>.json` (same
[cache dir](backends.md#environment-variables) the reaper's ledger uses), atomically:

```json
{"name":"rz-reuse-<hash>","image":"redis:8.6-alpine","ports":{"6379":54321},
 "createdIso":"2026-07-11T12:00:00Z","backend":"docker"}
```

A later `start()` of an equivalent container:

1. **Registry file exists** — verify the sandbox is actually running (a real backend query,
   not trusting the file blindly), then re-run the container's own wait strategy against the
   recorded host ports (bounded by the normal startup budget). Success: adopt — mapped ports
   come straight from the registry, no create call, `isRunning` is true immediately. Failure
   (not running, or the wait fails, or the registry file is unparseable): best-effort remove
   the stale sandbox and registry entry, then fall through to a fresh create as if there had
   never been a registry hit.
2. **No registry file** — allocate host ports normally, create under the reuse name, wait,
   then write the registry entry.
3. **Name collision on create** (another process's create won the same race) — re-enter the
   adopt path once against whatever the winner just wrote.

### The crash-mid-boot orphan

The registry entry is written only *after* a fresh reuse sandbox passes its wait strategy, so a
process that crashes (or is killed, or fails its own wait) strictly between `create` succeeding
and that write leaves a sandbox genuinely `Running` — invisible to reaping via `keepAlive` — with
no registry entry pointing at it. Left unhandled, that's exactly case 2 above ("no registry
file"), which would walk straight into creating a second sandbox under the same name: docker at
least 409s on the collision, but msb has been observed to happily start a second workload against
the same sandbox name, and the two fight over in-guest ports until every future start of that
identity times out. So once the adopt path has concluded there is no usable registry entry to
trust (missing, corrupt, or failed verification), rightsize asks the backend directly —
`findRunning(name)` — and best-effort `removeByName`s whatever it finds running under that name
before creating. This never fires on the concurrent-creator race (a registry entry appearing at
that point means another live process won and should be adopted — case 3 above), only on the
crash-orphan path where no registry entry ever showed up to adopt from.

## Stop leaves it running

`stop()` on a reuse-active container does **not** stop or remove the sandbox — that's the
entire point of reuse. It only clears this instance's own in-process bookkeeping (the handle,
its mapped-port view); the sandbox itself keeps running, unreachable from this instance until
the next equivalent container adopts it. It is never appended to the reaping ledger at any
point (see [Orphan Reaping](reaping.md#the-ownership-ledger)'s "reuse sandboxes are
structurally immune" note) — clean process shutdown, the sweep, and the watchdog all leave it
alone.

There is no explicit full-removal API in this release. To actually tear one down, use the
manual one-liner for your backend:

```sh
# docker
docker rm -f rz-reuse-<hash>

# microsandbox
msb stop rz-reuse-<hash> && msb rm rz-reuse-<hash>
```

or delete the stale entry from `<cache-dir>/reuse/` if you only want the *next* `start()` to
create fresh rather than adopt (the sandbox itself is untouched either way unless you also run
the command above).

## The network restriction

Reuse cannot be combined with `withNetwork()` — reuse identity covers only a container's own
configuration, never cross-container network topology, so a reused container's place on a
network can't be captured by the hash. Combining the two throws a typed error at `start()`
naming both knobs; drop one or the other.

## Interaction with reaping

`withReuse()` sets `keepAlive = true` on the underlying spec, which is exactly the flag
[Orphan Reaping](reaping.md) uses to keep a sandbox out of every own-run cleanup path a backend
runs — the ledger, the msb backend's shutdown-hook tracking, and the docker backend's run-id
label (a reuse container gets `dev.rightsize.reuse=<hash>` instead). Reuse and reaping are
deliberately the same mechanism seen from two angles: a sandbox that must survive its creating
process for reuse to work is, by construction, a sandbox reaping must never touch.

## CI guidance

**Do not enable `RIGHTSIZE_REUSE` on ephemeral CI runners.** A CI job's whole container/VM is
thrown away at the end of the run, so there is no "next process" to adopt the sandbox — reuse
there only costs you a sandbox that outlives the job with nothing left to clean it up (until
whatever reaps the *runner itself* does; rightsize's own reaping only reaps within one shared
cache dir/host, which a fresh-per-job runner never has). Reuse pays off on a long-lived dev
machine or a persistent CI worker with a stable cache dir across jobs — not a fresh container
per build.
