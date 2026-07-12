# Orphan Reaping

While a rightsize process is alive, normal lifecycle handles cleanup: `stop()`, JUnit's
[`@Sandboxed`](how-it-works.md) extension, the `Backends.active()` shutdown hook. The **only**
event that can leave a sandbox running with nothing left to stop it is the process itself dying
without running that cleanup — `SIGKILL`, an OOM-kill, a crashed CI step. Reaping exists purely
for that case, and is bound to the *process*, never to a test.

There are two layers, both driven by one ownership ledger: an always-on **init-time sweep**
that finds a dead process's leftovers the next time any rightsize process starts, and an
optional per-run **watchdog** that reaps within seconds of the crash instead of waiting for
that next process. Neither is a background daemon — the watchdog is one short-lived process per
run, spawned lazily and torn down with it.

## The ownership ledger

Every process that starts a sandbox writes a small set of files under the [rightsize cache
dir](backends.md#environment-variables) (the same directory `backend-microsandbox` provisions
into), keyed by that process's own run id:

- `runs/<run-id>.json` — written atomically before the process's first sandbox: its PID, the
  process's own start time (used to defeat PID reuse — see below), and which backend it's
  using.
- `runs/<run-id>.sandboxes` — one sandbox name per line, appended *before* the backend `create`
  call and removed *after* a successful stop. The file is therefore always a superset of what's
  actually live — a sandbox never exists without a line naming it.
- `runs/<run-id>.networks` — the same protocol for created networks.

This schema is identical byte-for-byte across rightsize's Kotlin, Rust, and TypeScript
libraries, because they all share one cache dir: a Kotlin process's sweep can judge — and reap
— a run left behind by the Node or Rust library just as readily as one of its own, as long as
that run's `backend` field names a backend the sweeping process is itself running (a docker
process never removes msb sandboxes, and vice versa; a cross-backend leftover simply waits for
a process on that backend to sweep it).

On a clean shutdown — the last container stopped, no networks left — the library deletes all
three files as soon as that happens, not just at process exit; a later sandbox on the same
process transparently recreates them. A crash leaves them in place, which is exactly the signal
the sweep and watchdog act on.

**Reuse sandboxes are structurally immune.** A `ContainerSpec` with `keepAlive = true` (used by
[container reuse](reuse.md)) is never appended to `.sandboxes` at all — not
reaping a sandbox you deliberately want to survive the process is the whole point of reuse, so
it can't depend on some later opt-out; it's simply never listed. The same immunity extends to
every own-run cleanup path a backend already runs today, not just the ledger: the msb backend
never adds a `keepAlive` sandbox to the set its constructor shutdown hook and `close()` sweep,
and the docker backend labels a `keepAlive` container `dev.rightsize.reuse=<12hex>` at create
time instead of the run-id label, so its `close()`'s run-id label filter never matches it
either.

## Liveness

A run is **alive** iff a process with the recorded PID exists *and* that process's own
OS-reported start time matches the recorded one within two seconds. The start-time check is
what defeats PID reuse: without it, a completely unrelated process that happens to land on a
recycled PID would make a genuinely dead run look alive forever.

## The init-time sweep

Runs exactly once per process, lazily, right after a backend resolves (the same
once-per-process gate that already registers the shutdown hook). For every other run's record
under the cache dir:

1. Skip the sweeping process's own run.
2. An unparseable record younger than an hour is left alone (it might just be mid-write by its
   still-alive owner); older than an hour, it's presumed abandoned and cleaned up.
3. A run whose backend doesn't match the sweeping process's own backend is left for a process on
   that backend.
4. A run that's still alive is left completely untouched.
5. A dead run is reaped: every name in its `.sandboxes` is removed via the active backend's
   `removeByName` (best-effort — "not found" is silently ignored, since two processes racing to
   sweep the same dead run is expected and harmless), every network in `.networks` is removed
   the same way, then all three of that run's files are deleted.

## The watchdog

Spawned once per process, lazily, right before the process's first sandbox create (once the run
record already exists on disk). A small script — POSIX `sh` on macOS/Linux, PowerShell on
Windows — written once under `<cache-dir>/reaper/` and reused by every run (its content is
generic, parameterized entirely by the arguments a given run passes it), spawned detached with
its stdin connected to a pipe whose write end this process holds and never explicitly closes.
All other stdio is redirected to null, so CI runners never hang on an inherited handle.

That pipe is the entire mechanism: as long as the process is alive, the write end stays open and
the script blocks reading stdin. The moment the process exits — cleanly or via `SIGKILL` — the
OS closes that file descriptor, the script's read hits EOF, and it:

1. Kills every name in `runs/<run-id>.sandboxes` via the backend CLI (an msb run gets `stop`
   then `rm`; docker gets one `docker rm -f`), retrying once if the output carries msb's
   `error: database error:` signature — the same transient-migration-race classifier the boot
   path itself retries on.
2. Removes every network in `runs/<run-id>.networks`.
3. Deletes the run's three ledger files.

An empty or missing `.sandboxes` (the ordinary clean-shutdown case, if the watchdog somehow
outlives the process's own cleanup) just deletes the record files — reaping on EOF is
idempotent, so there's no separate "was this a crash" signal to compute.

**The docker-remote caveat.** A watchdog is a local process: it cannot outlive the machine it
runs on. If a CI runner is torn down mid-run against a *remote* docker daemon, the watchdog dies
with it before it can fire, and the containers it would have reaped keep running on that daemon.
The next rightsize process to talk to that same daemon still finds and sweeps them — the
init-time sweep doesn't care where its previous run happened, only that the record and the
sandbox are both still there — so nothing leaks permanently, it just isn't reaped within
seconds the way a local-daemon crash is.

## The `RIGHTSIZE_REAPER` switch

| Value | Sweep | Watchdog |
|---|---|---|
| `on` (default) | yes | yes |
| `sweep` | yes | no |
| `off` | no | no |

Unknown values are treated as `on`. Set `RIGHTSIZE_REAPER=sweep` to skip spawning the watchdog
process entirely (e.g. a sandboxed CI environment that can't spawn detached children) while
still getting next-run cleanup; set `RIGHTSIZE_REAPER=off` to disable reaping altogether.

## What this doesn't cover

Orphan reaping only reacts to *process* death. If your own code forgets to call `stop()` while
the process stays alive, nothing here helps — that's what `@Sandboxed`/`@Container`, `try`/
`finally`, and the `Backends.active()` shutdown hook are for. See [How It
Works](how-it-works.md) for the full container lifecycle.
