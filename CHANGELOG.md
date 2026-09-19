# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to adhere to [Semantic Versioning](https://semver.org/) once it
reaches its first tagged release.

## [Unreleased]

Nothing yet.

## [0.7.11] - 2026-09-19

### Added

- **UDP port exposure (Phase 1).** `PortBinding` gains a `protocol` field (`PortProtocol.TCP` |
  `PortProtocol.UDP`, defaulting to `TCP`) — every existing spec/producer still means TCP, and a
  checkpoint-registry entry written before this field existed reads it back as `TCP`. A new
  builder, `GenericContainer.withExposedUdpPorts(vararg ports: Int)`, backed by a field entirely
  separate from `withExposedPorts`, declares guest ports to publish over UDP; a new, distinct
  accessor, `getMappedUdpPort(guestPort: Int): Int` (never an overload of `getMappedPort`),
  resolves the assigned host port. A container may expose the SAME guest port number on both
  protocols at once (e.g. DNS's 53) — the two protocols' mapped ports never collide. UDP host
  ports are allocated by probing a UDP socket, not a TCP one (a TCP bind proves nothing about an
  independent UDP port table). Docker publishes each UDP port with a native `<port>/udp` binding;
  the microsandbox backend publishes it with `-p host:guest/udp` on both `msb run` and `msb
  restore` (a checkpoint reboot, or an ordinary `fromCheckpoint(...).start()` restore, re-publishes
  a UDP mapping exactly like the original boot). `CheckpointSpec` gains `exposedUdpPorts`, carried
  through `checkpoint()`/`fromCheckpoint` the same way `exposedPorts` always has, and reads back as
  empty for a pre-UDP registry entry. `ReuseIdentitySpec` gains `exposedUdpPorts` (folded into the
  reuse identity hash the same omit-when-empty way `diskLimitMb`/`tmpfsRootMb`/`networkDisabled`
  are, so the pinned cross-language hash vector stays pinned) — a container exposing a port over
  TCP and one exposing the same numeric port over UDP are never interchangeable for reuse.
  `NetworkLink` gains the same trailing `protocol` field; `Network` carries a UDP-exposed member's
  mapped port through as a UDP-tagged link. **Wait-strategy caveat:** a UDP-exposed port is
  invisible to `Wait.forListeningPort()` by construction — the default wait only ever enumerates
  `withExposedPorts`-declared ports — so a container exposing only UDP ports is vacuously ready
  under the default wait; use `Wait.forLogMessage(...)` (or a custom `AbstractWaitStrategy`) for a
  UDP-only service instead. **microsandbox network limitation:** msb has no direct guest-to-guest
  networking at all — `Network`'s emulation there is a TCP exec-tunnel relay with no UDP
  equivalent — so joining an msb `Network` where any member's link would be UDP now fails
  `start()` fast with a typed `UnsupportedByBackendException` (before any tunnel/hosts work),
  naming the guest port/alias and pointing at two remedies: the docker backend for real
  container-to-container UDP, or publishing the UDP service on a host port
  (`withExposedUdpPorts` + `getMappedUdpPort`) instead of a network alias. Container-to-container
  UDP over a `Network` remains Docker-only in this phase; see
  [Networking](docs/concepts/networking.md#udp) for the full picture. Existing public signatures
  and behavior are unchanged — everything here is additive.

## [0.7.10] - 2026-09-18

### Changed

- **The pinned microsandbox release is now 0.7.1** (from 0.6.18). Upstream's 0.7.0/0.7.1
  bring guest stream-symlink fixes at agent startup, configurable TCP/UDP connection
  limits, opt-in startup/network traces, and explicit snapshot flush policies (a new
  optional `--flush` flag; full snapshots — the shape this library's checkpoints use —
  keep their existing default). One driven CLI flag was renamed upstream —
  `msb snapshot create --from` became `--from-sandbox` — and this library's
  checkpoint machinery now emits the new spelling; nothing changes for callers.
- **Checkpoint restore on the microsandbox backend now goes through `msb restore`, not
  `msb run --from-snapshot`.** Upstream 0.7 removed `--from-snapshot` from `run`
  entirely (clap rejects it outright; `msb run` itself now bails toward `msb restore`
  for a snapshot-shaped source) and moved restoring into a dedicated `msb restore <ref>
  --name <name>` command. Restoring a disk-scope snapshot (the only kind this library's
  checkpoints ever create) is inherently a cold boot of the captured disk without
  resuming captured RAM/processes — the same filesystem-only semantics this library's
  checkpoints have always had — with **no** `--disk-only` flag: msb 0.7.1 rejects that
  flag outright for a disk-scope source (`invalid config: disk_only requires a full
  snapshot with checkpoint state`); an earlier revision of this same change on this
  branch passed it, which is now corrected. `restore` carries no `-e`/`--env` flag and
  no command override at all, unlike `run`: restore replays the sandbox's own captured
  configuration instead, so this library never re-passes env/command on a restore boot.
  `GenericContainer.fromCheckpoint(cp)` still lets a caller override env/command with
  `withEnv`/`withCommand` afterward — genuinely
  changing either now throws the new typed `CheckpointRestoreOverrideUnsupportedException`
  before any backend call on microsandbox (new `BackendCapabilities.checkpointRestoreOverridable`,
  `false` there, `true` on Docker, which has no such restriction); re-supplying exactly
  what the checkpoint already captured never throws. The same guard also covers
  `withDiskLimit`/`withTmpfsRoot`/`withNetworkDisabled` called after `fromCheckpoint`: since
  a checkpoint never captures disk/network geometry in the first place, any use of these
  three after `fromCheckpoint` is now always rejected with the same exception on
  microsandbox, rather than silently booting with the snapshot's own geometry instead of
  the one requested. See the ref-shape entry below for the one other public change this
  same msb 0.7.1 pin required.
- **Checkpoint refs on the microsandbox backend now point at msb's own snapshot store
  layout, not a path this library mints.** As of msb 0.7.1, `msb snapshot create
  --from-sandbox <sandbox> <name> --dest-dir <D>` no longer writes its artifact at
  `<D>/<name>` — `<name>` only ever shows up in msb's own index and in `snapshot
  inspect` output — but at `<D>/<sandbox>/snap_<32-hex digest>`, a path msb decides on
  its own. `Checkpoint.ref` on this backend is therefore now that captured path (e.g.
  `<cache-dir>/checkpoints/<sandbox>/snap_<hex>`) instead of the previous
  `<cache-dir>/checkpoints/rz-ckpt-<12-hex-or-name>` shape — a **user-visible ref-shape
  change**: `SandboxBackend.createCheckpoint` now returns the EFFECTIVE ref (parsed,
  defensively, from `snapshot create`'s own last stdout line — a clear error names the
  unparsed output if it isn't an absolute path) rather than always the ref it was asked
  to write to, the same "effective ref may differ from the input" shape
  `SandboxBackend.importCheckpoint` already had; Docker's implementation is unaffected,
  always returning its input ref unchanged. `Checkpoint.remove`/`removeCheckpoint` and
  `Checkpoint.find`'s staleness probe now pass that full artifact path straight to `msb
  snapshot rm <ref> -f`/`msb snapshot inspect <ref>` — msb 0.7.1 resolves neither
  reliably against a bare name any more, so the previous basename-only `snapshot rm`
  call (and the pure-filesystem shortcut `hasCheckpoint` used for a path ref) no longer
  worked and have been removed. One related, separately-discovered msb 0.7.1 behavior worth
  knowing: removing the NEWEST (head) snapshot of a source sandbox while an older
  sibling from the same source still exists is refused by msb itself — this library
  propagates that refusal as-is (both removal calls are best-effort and never inspect
  msb's exit code, so it's silently absorbed rather than surfaced) and does not attempt
  automatic head rotation; see docs/checkpoints.md's Cleanup section for the by-hand
  remedy.
- **The MinIO module's default image moved to `quay.io/minio/minio:latest`.** Docker
  Hub's `minio/minio` repository was removed upstream (`docker pull minio/minio` now
  fails "repository does not exist"); the floating default now points at MinIO's
  maintained `quay.io` mirror. Compatibility checking stays registry-agnostic, so a
  `minio/minio:<tag>` override is still accepted alongside a `quay.io/minio/minio:<tag>`
  one — only the default moved.
- **`msb snapshot load` (checkpoint-archive import) is now always given `--dest
  <cache-dir>/checkpoints`**, this library's own checkpoints directory — the same one
  `createCheckpoint`'s `--dest-dir` already writes under — instead of defaulting into
  msb's own global `~/.microsandbox/snapshots/` store, so an imported artifact and a
  locally created one now live under the same rightsize-owned tree.
  `Checkpoint.exportTo`/`importFrom` keep their exact signatures and archive-file
  behavior — only the underlying `msb snapshot load` invocation and ref derivation
  changed. **A second user-visible ref-shape change, alongside the one `createCheckpoint`
  already got**: `Checkpoint.importFrom`'s effective ref is now the LOADED ARTIFACT's own
  absolute path — parsed, defensively, from `snapshot load`'s own last stdout line, the
  same `?: error(...)` shape `createCheckpoint`'s own parsing already has — instead of a
  bare digest-dir name (`sha256-<16hex>`) cross-referenced against `msb snapshot list
  --format json`; that list-based lookup no longer applies to anything this library mints
  and has been removed. Docker's `importCheckpoint` is unaffected, always returning its
  input ref unchanged. On the already-exists-as-success outcome specifically (re-importing
  an archive whose content is already present), msb 0.7.1's exact stdout shape for that
  branch is not independently confirmed, so ref parsing tries stdout first and falls back
  to stderr — the one shape this library has verbatim evidence for, from msb 0.6.8 — rather
  than assuming either one outright; only if neither stream yields an absolute path does
  `importFrom` throw.

### Fixed

- **A restored microsandbox container's workload is now actually running again.** Confirmed
  empirically against the real msb 0.7.1 binary: `msb restore` brings a sandbox up with only
  `agentd` inside — the checkpointed command never re-runs on its own (`msb start` on the same
  sandbox boots equally idle, and `msb run` has no snapshot option at 0.7.1) — so every restored
  container used to silently break this library's own checkpoint contract (workload running,
  ports served, wait strategies satisfiable, logs flowing). This backend now revives the
  workload itself: once a restore (both `GenericContainer.fromCheckpoint(cp).start()` and
  `checkpoint()`'s own re-boot of the source container) reaches Running, it spawns `msb exec
  [-e KEY=VALUE]... <name> -- <command>` as a long-lived attached child — carrying the
  checkpoint's own env, which `msb restore` itself has no flag for at all — and that session
  becomes the boot's new supervising attached child: it's reaped on `stop()` exactly like an
  ordinary attached `run` child, and the container's wait strategy now runs against a workload
  that's actually there. The workload command comes from, in order: the checkpoint's own explicit
  command when it had one (the common case — nothing else to do here); otherwise a cmdline
  CAPTURED FROM THE GUEST at checkpoint time, before the source sandbox is stopped (walks
  `/proc` for the first non-kernel child of PID 1 and reads its `/proc/<pid>/cmdline`) and
  persisted keyed by the checkpoint's own ref, for a container that ran its image's default
  entrypoint with no explicit command; a checkpoint with neither — a registry predating this
  capture, or one whose capture itself failed — now refuses to restore at all, with a typed
  `CheckpointMissingWorkloadCommandException`, rather than silently booting an idle sandbox that
  merely looks started. No public API changed: `Checkpoint`/`CheckpointSpec` and the named-
  checkpoint registry's file format are untouched: the captured cmdline is exclusively an
  internal, additive lookup keyed by ref, invisible to `Checkpoint.list()`/`find()`/`exportTo`.
- **`msb restore` retries once on Windows' deferred snapshot-file-release access-denied error.**
  Immediately after `createCheckpoint`'s own stop -> snapshot -> rm teardown of the source
  sandbox, `msb restore` reading the just-written snapshot artifact can occasionally race
  Windows' own deferred file-handle release (msb's docs describe this lag) and fail with `error:
  io error: Access is denied. (os error 5)`. That specific signature — the access-denied phrase
  together with the Rust-appended errno suffix, so an unrelated permission failure is never
  misclassified — is now treated as a transient boot failure and retried, bounded, with a short
  backoff, the same shape this library's other classified boot-transient retries already use.
  The classifier itself is platform-agnostic code; the signature simply never occurs on Unix.
- **`createCheckpoint`'s reboot now restores under a freshly generated sandbox name, not the
  checkpointed container's own original name.** msb's own existence check for a restore target
  is "DB record present OR on-disk directory present", and on Windows the just-`rm`'d sandbox's
  directory has been observed on CI outliving its DB record by multiple seconds under load — so a
  same-name restore issued into that gap can refuse "already exists" for as long as the directory
  lingers, which is not a fixed bound no retry budget can reliably outlast. Restoring under a
  freshly generated name instead (`rz-<runId>-<n>`, the exact same generator/counter an ordinary
  boot already uses) never collides with the just-removed sandbox's own lingering directory at
  all, sidestepping the whole lag rather than racing it. Ports, env, memory limit, and captured
  disk state all still carry over unchanged; only the sandbox's own name does not — that name was
  always an implementation detail of the reboot, never a documented contract, so this is a
  behavior note, not an API change (no public signature moved). The live container handle's
  identity is rewritten in place before the reboot is attempted, so `exec`/`logs`/`stop`/a later
  `removeCheckpoint` all keep working transparently against it with no caller-visible change, and
  the reaper's run ledger tracks the fresh name the same append-before-create way it tracks an
  ordinary boot, leaving the old name's entry for its own not-found-tolerant sweep. This refines
  the entry immediately below (the post-`rm` `msb ls` name-release wait, and the reboot's own
  already-exists retry budget): both are KEPT, unchanged, as dormant defense in depth — a freshly
  generated name colliding with something else live is now vanishingly unlikely, never removed —
  but neither one gates the common case anymore, since a fresh name has nothing of its own to
  race in the first place.
- **`createCheckpoint` waits out msb's asynchronous sandbox-name release on Windows before
  rebooting from the snapshot**, polling `msb ls` (bounded, briefly) after `rm`. `msb ls` only
  proves the sandbox's database record is gone, though — msb 0.7.1's `restore` itself also
  refuses "already exists" while the sandbox's on-disk directory still exists, and that directory
  has been observed on Windows CI outliving the database record by more than 3.5 seconds under
  load. The reboot itself retries on that refusal with a genuine ~30-second budget (2-second
  intervals) instead of the handful of retries at a few hundred milliseconds this previously
  shipped with, so the retry actually outlives the lag instead of merely hedging against it. (See
  the entry above: as of the fresh-name reboot, this wait and this retry budget are dormant
  defense in depth rather than what the common case depends on.)
- **Two leftover-cleanup gaps in the fresh-name reboot above, closed.** The fresh name is now
  added to this backend's own own-run cleanup set (the JVM shutdown hook, and `close()`) BEFORE
  the reboot is even attempted, not only once it succeeds — a reboot whose `msb restore` reaches
  Running but then fails reviving the checkpointed workload (a missing workload command, or a
  revival command that exits almost immediately) used to leave a genuinely live sandbox under the
  fresh name outside every own-run cleanup path this library runs; it's now covered by the same
  net an ordinary boot always has been. Separately, `GenericContainer.checkpoint()`'s own
  `LiveContainers` re-key (needed because the backend rewrites the live handle's name in place
  before attempting its reboot, not only after it succeeds) now runs whether that reboot throws
  or not, instead of only on a successful return — a failed reboot after a rename used to leave
  `LiveContainers`, and therefore `Diagnostics.render`, permanently mislabeling the container
  under its stale pre-checkpoint name. Neither change touches any public signature.
- **`createCheckpoint`'s reboot now mints a FRESH sandbox name on every retry, never the same one
  twice.** msb 0.7.1's `restore` validates the snapshot artifact first (an integrity failure exits
  1 with no sandbox record left behind), but a failure AFTER validation — chiefly Windows'
  deferred-file-release access-denied error — can still leave the attempted name itself as a
  STOPPED sandbox record, and a same-name retry into that gap then collides with its own leftover
  for as long as msb's teardown of it takes. That was exactly what msb-windows CI hit: the
  already-exists-refusal retry and the access-denied retry each used to retry the SAME name for
  their whole budget, so an access-denied failure on attempt 1 left a record that every same-name
  retry then collided with, burning the entire ~30s budget without ever advancing. Both retries now
  best-effort `msb rm` the just-failed name and mint another fresh name from the same generator
  ordinary boots use before retrying, bounded by the same wall-clock budget as before (an outer
  bound on total retry time across as many names as it takes, not a name-specific attempt count).
  The Windows access-denied retry no longer runs its own same-name loop for this path at all — it
  now surfaces the raw, unretried failure so the fresh-name loop above is what classifies and
  retries it. (An ordinary `GenericContainer.fromCheckpoint(cp).start()` restore, outside
  `checkpoint()`'s own reboot, still kept its own separate same-name access-denied retry at this
  point in the branch's history — see the entry further below for why that was itself a bug, fixed
  in a later round.) Every attempted name — not just the first —
  is tracked in the reaper's run ledger before its own restore attempt, the same append-before-create
  discipline an ordinary create already gets, so a process dying mid-retry still leaves every
  attempted name discoverable by the ledger's own not-found-tolerant end-of-run sweep. No public
  signature changed; the sandbox's name across a checkpoint was never a documented contract. A
  related, previously-incorrect test expectation is also fixed: the reaper ledger is no longer
  asserted to be untouched by the checkpoint/restore cycle (that was only ever true back when the
  reboot reused the source sandbox's own name) — it now gains exactly the fresh name(s) the cycle
  actually minted, one in the happy path, with every prior entry preserved unchanged.
- **`createCheckpoint`'s reboot now escalates to a job-free WMI broker after a Windows
  access-denied restore failure, instead of retrying direct spawns that can never succeed.**
  A live diagnostic campaign against msb-windows CI traced `RestoreAccessDeniedException` to a
  second, distinct cause beyond the deferred-file-release lag documented above: `msb restore` is
  detached-by-design, so it always spawns a fresh `msb.exe` as the VM supervisor, and on Windows
  that detached spawn always passes `CREATE_BREAKAWAY_FROM_JOB`. When the JVM running this library
  itself sits inside a Windows job object that does not grant breakaway rights — precisely how a
  Gradle test worker or a cargo-test binary is confined under a CI runner's own job — that spawn is
  denied `ERROR_ACCESS_DENIED` deterministically, not transiently: no retry budget, at any name,
  ever clears it, only a different launch mechanism does. `msb restore --trace` output confirms
  every earlier stage (artifact resolution, the sandbox-record insert, the writable-disk grow)
  succeeds first, and only the process launch itself is refused — nothing is locked, no file handle
  is held. The reboot's fresh-name walk now treats this the same way it already treats an
  already-exists refusal, but adds one more step: the FIRST attempt of a reboot is still always a
  direct spawn (zero change on a healthy host), but from the first attempt that hits this specific
  Windows access-denied classification onward, every remaining attempt launches `msb restore`
  through `Invoke-CimMethod -ClassName Win32_Process -MethodName Create` instead — WMI's created
  process is parented by the WMI provider host, outside this JVM's own job hierarchy entirely, so
  the identical restore invocation that a direct spawn cannot make succeeds this way. This never
  engages on non-Windows hosts, and a broker-infrastructure hiccup (`powershell.exe` missing, WMI
  itself refusing to create anything) simply falls back to a direct spawn for that one attempt
  rather than becoming a new point of failure of its own — every existing budget, fresh-name, and
  cleanup behavior around the retry loop is unchanged. No public signature moved; the launch
  mechanism behind a checkpoint restore was never part of this library's documented contract. A fix
  for the underlying spawn behavior is also being reported upstream to microsandbox.
- **An ordinary `GenericContainer.fromCheckpoint(cp).start()` restore now gets the exact same
  fresh-name-per-attempt policy and broker escalation as `createCheckpoint`'s own reboot above,
  instead of retrying a Windows access-denied failure under the SAME sandbox name.** Live msb-
  windows CI evidence: `MsbCliBackend.classifyRestoreExit` throwing `SandboxNameCollisionException`
  ("sandbox '...' already exists") on the retried attempt, for restores that had already passed
  their own `checkpoint()` call cleanly — the ordinary path's inline same-name retry was retrying
  directly into the stopped record its own first, denied attempt had just left behind, the identical
  mechanism the reboot's own fresh-naming fix (two entries above) already addresses, just one call
  site lower. `start()` now routes a restore boot (`spec.checkpointRef != null`) through the SAME
  fresh-name walk `createCheckpoint`'s reboot uses — the two now share one implementation rather
  than each carrying its own copy — distinguished only by which name attempt 1 is made under:
  the reboot's own fresh name (the source sandbox is already gone by then) versus, here, the
  container's own originally minted name, already live and already tracked in the reaper's run
  ledger before `start()` is ever called. Attempt 1 stays a direct spawn under that original name,
  unretried inline, exactly like an ordinary boot always has; only a classified failure
  (`RestoreAccessDeniedException` or `SandboxNameCollisionException`) advances to a freshly minted
  name, best-effort `msb rm`-ing the failed one first — and, on Windows, once an attempt has hit the
  access-denied signature specifically, every remaining attempt escalates through the same job-free
  WMI broker the reboot's own escalation uses, for the identical `CREATE_BREAKAWAY_FROM_JOB`
  job-object reason. No separate re-keying step was needed to make this safe: the winning name is
  written straight into the live `Handle`'s own mutable spec (the same object
  `GenericContainer.start()` already holds), so every operation against it — `exec`/`logs`/`stop`/
  `remove`, and this backend's own own-run cleanup tracking — already resolves the winner with
  nothing left pointing at the stale name; unlike `checkpoint()`, `GenericContainer.start()` never
  registers `LiveContainers` under a name before this call returns, so there is nothing there to
  fix up either. Bounded by the exact same wall-clock budget the reboot's own walk uses (not a
  separate one). Ordinary image boots (`msb run`, no checkpoint involved) are completely
  unaffected — this only ever engages for a restore. No public signature changed.

## [0.7.9] - 2026-09-10

### Changed

- **The pinned microsandbox release is now 0.6.18** (from 0.6.17). Upstream changes
  are release-pipeline fixes plus incremental network-stack work (policy, secrets,
  proxy internals). No CLI surface this library drives changed.

## [0.7.8] - 2026-09-04

### Changed

- **The pinned microsandbox release is now 0.6.17** (from 0.6.16). Upstream changes
  relevant here: outbound SOCKS4/SOCKS5 proxy support (new, additive `--proxy` flags
  this library does not yet drive), a fix preserving the released order of state-db
  migrations across upgrades, and a centralization of sandbox CLI option parsing. No
  CLI surface this library drives changed.

## [0.7.7] - 2026-09-01

### Added

- **The docker backend's per-OS default daemon endpoint is now documented and unit-tested in
  this repo**, not just implicit in docker-java's own resolution: Windows gets
  `npipe:////./pipe/docker_engine`, the named pipe Docker Desktop's WSL2 backend serves its API
  over, while every other OS keeps `unix:///var/run/docker.sock`. `DOCKER_HOST` still overrides
  either default when set, and the active docker CLI context (`~/.docker/config.json`) still
  takes precedence over the plain default the same way it always has. The new
  `DockerHost.resolve()` seam (`backend-docker`) makes this OS/env-driven choice
  unit-testable — but is deliberately never fed back into `DefaultDockerClientConfig.Builder`
  ahead of `build()`, since doing so (an earlier draft of this change did exactly that) silently
  defeats context resolution on every OS, not just where `DOCKER_HOST` is set; see
  `DockerContextResolutionTest` for the reproduction and `DockerHost`'s doc comment for why. The
  `docker-java-transport-zerodep` transport this backend already depends on already special-cases
  the `npipe` scheme with a JNA-backed named-pipe socket (`NamedPipeSocket`), and JNA is already
  pulled in transitively — so no new dependency was needed, and no behavior actually changed on
  either platform; `docs/backends.md` is corrected to match (it previously and incorrectly said
  the zerodep transport speaks unix sockets only). `DockerBackendProvider.isSupported()` now
  also requires the daemon's `GET /version` to report a Linux OS, not just that it's reachable,
  so a Windows-containers dockerd (which answers a plain ping fine but can't run this backend's
  Linux images) is correctly reported unsupported instead of picked and then failing at boot,
  and that `GET /version` query itself now carries the same short, bounded timeout `DockerBackend`
  already uses so a daemon that accepts a connection but stalls before answering can't hang
  detection indefinitely.

## [0.7.6] - 2026-08-29

### Changed

- **The pinned microsandbox release is now 0.6.16** (from 0.6.15). Upstream changes
  relevant here: network address slots are recycled instead of exhausting after many
  sandbox creations, single-file mounts are properly isolated, and a failed boot now
  renders a structured boot error in `msb logs`. The CLI surface this library drives is
  unchanged, but 0.6.16's sandbox-lifecycle rework does change observable timing — see
  the Fixed entry below — and 0.6.16 migrates the shared msb state database on first run.
  **If you point `MSB_PATH` at your own msb binary, don't downgrade it below 0.6.16 once
  that `MSB_HOME` has been touched by a 0.6.16 binary**: an older binary refuses a
  migrated home outright with `database schema is newer than this msb binary`.

### Fixed

- **A quickly-completing workload no longer fails `start()` on msb 0.6.16.** 0.6.16's
  convergent-lifecycle rework means a sandbox running a workload that finishes fast (a
  short build or script) may never be observed in the `Running` state at all — only
  `Starting`, then `Stopped` once the attached `msb run` child exits. This backend used
  to treat any pre-`Running` exit as a failed boot; it now checks, on a clean (exit 0)
  early exit, whether the sandbox is `Stopped` in `msb ls` and its system log carries the
  boot-completion marker (`msb logs <name> --source system`) — both together mean the
  workload ran to completion, not that the boot died, and `start()` now succeeds for it.
  Any other early exit (non-zero, or missing either signal) keeps today's failure
  behavior unchanged.

## [0.7.5] - 2026-08-26

### Changed

- **The pinned microsandbox release is now 0.6.15** (from 0.6.14). Upstream changes
  relevant here: host DNS on Windows now routes through the system resolver, file
  copies on NTFS only copy allocated ranges, and read-only mounts no longer get
  write-probed. No CLI surface this library drives changed.

### Fixed

- **Host ports that hit a bind conflict are no longer eligible for the immediate retry.**
  The port-retry loop used to return a conflicted port to the allocator before the next
  attempt, so the OS could hand the same proven-contended port straight back. Conflicted
  ports now stay quarantined until the retry loop exits.

## [0.7.4] - 2026-08-22

### Changed

- **The pinned microsandbox release is unified back to a single version, 0.6.14, on
  every platform.** The per-platform split introduced in 0.7.2 is retired: upstream's
  msb_krun/msb_krun_devices 0.1.32 bump (microsandbox issue #1426) removes the
  Windows-only code path that started console-port delivery at `PORT_READY` — kernel
  probe time, before any guest process has the port open, so the guest driver
  discarded the bootstrap frame — and ports now start delivery at `PORT_OPEN` instead,
  matching unix. 0.6.14 was channel-verified on a windows-2025 GitHub runner with the
  official binary: sandboxes boot, exec works through the agent relay, the sandbox
  survives well past the old 60-second death window, and `msb logs` streams guest
  output. 0.6.12 through 0.6.14 change nothing else of substance for the CLI surface
  this library drives, so unifying both platforms on 0.6.14 does not change behavior
  on macOS or Linux.

  **If you point `MSB_PATH` at your own msb binary on Windows, avoid 0.6.10 through
  0.6.13** — those releases still hit the bootstrap regression on every container
  start. Use 0.6.9 or 0.6.14+.

## [0.7.3] - 2026-08-21

### Changed

- **The pinned microsandbox release is now 0.6.12 on macOS and Linux; Windows stays
  on 0.6.9.** The Windows-only bootstrap regression introduced in 0.6.10 is still
  present in 0.6.11 and 0.6.12: since 0.6.10, guest bootstrap moved off the kernel
  command line onto a one-shot pre-boot console frame, and on Windows hosts that
  frame never reaches agentd (the guest's PID 1), so agentd times out after 60
  seconds and the guest dies. The sandbox can briefly report Running, but the agent
  relay endpoint is never created, so exec/logs/ping can never connect. There is no
  environment variable, CLI flag, or other client-side workaround. macOS and Linux
  are unaffected. The CLI surface this library drives is identical from 0.6.9
  through 0.6.12 (no core source changes landed in that span), so the per-platform
  pin does not change behavior — the provisioner simply keeps routing Windows around
  the broken releases until upstream fixes bootstrap delivery.

  **If you point `MSB_PATH` at your own msb binary on Windows, keep it at 0.6.9** —
  a 0.6.10, 0.6.11, or 0.6.12 binary there will hit the regression on every
  container start.

## [0.7.2] - 2026-08-19

### Changed

- **The pinned microsandbox release is now 0.6.10 on macOS and Linux; Windows stays
  on 0.6.9.** msb 0.6.10 has a Windows-only regression: its pre-boot guest bootstrap
  message never reaches the guest agent on Windows hosts, so every sandbox exits
  about 70 seconds after spawn without the agent ever coming up. macOS and Linux are
  unaffected. The two releases are identical across every CLI surface this library
  drives, so the per-platform pin does not change behavior — the provisioner simply
  routes Windows around the broken release until upstream fixes it.

  **If you point `MSB_PATH` at your own msb binary on Windows, keep it at 0.6.9** —
  a 0.6.10 binary there will hit the regression on every container start.

## [0.7.1] - 2026-08-16

### Changed

- **The pinned microsandbox release is now 0.6.9** (was 0.6.8). No CLI surface this
  library drives changed, so no action is needed — the provisioner downloads and
  checksum-verifies the new release automatically, and `MSB_PATH` setups validated
  against 0.6.8 keep working. 0.6.9 also fixes two upstream issues this library
  carried defenses for: the Windows snapshot-save flush failure (the salvage path
  stays in place and now simply never fires) and the concurrent-pull image-cache
  race (the heal path likewise remains as a safety net).

## [0.7.0] - 2026-08-04

### Added

- **`withDiskLimit(megabytes)`** caps a container's writable root disk — msb-only
  (`--root-disk <mb>M`); Docker runs without a ceiling and ignores it. On an msb reboot
  the cap can only grow, never shrink. Mutually exclusive with `withTmpfsRoot` — setting
  both throws `RootDiskConflictException` at `start()`.
- **`withTmpfsRoot(megabytes)`** backs the root disk with RAM instead of storage —
  faster ephemeral containers, no disk residue — msb-only; Docker ignores it. Must fit
  inside the guest's memory: msb defaults to 512M when `withMemoryLimit` is unset, and
  setting both with a tmpfs size larger than the memory limit throws
  `TmpfsRootExceedsMemoryException` at `start()`. A tmpfs root cannot be checkpointed —
  `checkpoint()` throws `TmpfsRootCheckpointException` before touching anything, and a
  refused named re-checkpoint leaves the existing checkpoint intact. msb also rejects
  any root-disk setting on a `fromCheckpoint` restore before boot, since the snapshot
  already pins the root disk.
- **`withNetworkDisabled()`** blocks public-internet access — msb-only, emitted as
  `--net private`: published ports keep serving and private-range network links keep
  working, but outbound connections to the public internet fail. Docker ignores this
  flag entirely — there's no portable way to block egress there while keeping published
  ports. Cannot be combined with `withNetwork()` — throws `NetworkDisabledConflictException`
  at `start()`.

### Changed

- **msb checkpoint artifacts now live under `<cache-dir>/checkpoints/`** (the same
  rightsize cache directory the named-checkpoint registry already uses —
  `~/.cache/rightsize` on macOS/Linux, `%LOCALAPPDATA%\rightsize` on Windows), written
  via msb's own `--dest-dir` instead of its default `~/.microsandbox/snapshots/`
  location. `Checkpoint.ref` for the microsandbox backend is now the absolute artifact
  path — `ref` stays an opaque string in the public API, and a bare-name ref from an
  earlier release still restores. The snapshot still shows up in `msb snapshot list`
  (msb keeps its own global index); removing it through the library cleans both the
  index entry and the artifact. `exportTo`/`importFrom` are unaffected.

### Fixed

- **The boot-retry classifier now also recognizes msb's second install-lock phrasing** —
  `another microsandbox install operation is in progress until <timestamp>`, not just the
  original `microsandbox install operation in progress until <timestamp>`. A boot that hit
  the second wording used to fail outright instead of being retried like the first.

## [0.6.2] - 2026-08-01

### Fixed

- A failed container create no longer leaks its pre-allocated host ports or its
  reaper ledger entry: the create-error path now returns the ports to the
  allocator and removes the just-appended ledger line, as the reuse path and the
  start-error path already did. Without this, every failed create attempt
  permanently retired its ports from the pool for the life of the process.

## [0.6.1] - 2026-08-01

### Changed

- **The pinned microsandbox release is now 0.6.8** (was 0.6.6). The provisioner
  downloads and checksum-verifies it automatically, so no action is needed for the
  usual setup.

  **If you point `MSB_PATH` at your own msb binary, it must be 0.6.8 or newer.**
  0.6.8 renamed three CLI surfaces this library drives, and the calls it now emits do
  not exist in 0.6.6:

  | 0.6.6 | 0.6.8 |
  |---|---|
  | `run --snapshot <ref>` | `run --from-snapshot <PATH_OR_NAME>` |
  | `snapshot export <ref> <dest>` | `snapshot save <SNAPSHOT> <OUT>` |
  | `snapshot import <archive>` | `snapshot load <ARCHIVE> [DEST]` |

  Checkpoint restore and checkpoint archives are the affected features; both fail
  outright against an older binary rather than degrading quietly.

- **A loaded snapshot's effective ref is now a bare 64-character digest**, where 0.6.6
  produced a `sha256-<16hex>` directory name. Nothing in the public API changes — the
  ref was always opaque and content-addressed — but code that pattern-matched the old
  shape will need updating.

- **`FileMount.readOnly` now defaults to `false`, and the flag is genuinely enforced on
  the microsandbox backend.** It previously never reached msb, so every mount there was
  writable regardless of the flag; the Docker backend enforced it all along. What a
  caller observes: a default `withCopyFileToContainer` mount on the Docker backend was
  read-only before and is writable now — pass `readOnly = true` to get the old Docker
  behavior, which both backends now honor as a guest-side write block. The mount is a
  view of the host file, not a copy, so a guest write reaches the host file itself.

### Fixed

- **File mounts work on Windows.** msb 0.6.7 broke every start-time file mount there:
  its mount-spec parsing splits a token-less spec at the drive letter's colon, both on
  the CLI spec and again on an internally rebuilt one. Every mount spec this backend
  emits now carries an explicit `ro`/`rw` access token plus `nodev`, which keeps both
  layers parseable. `nodev` is meaningless for a single-file mount — there are no
  device nodes to block.

- **Checkpoint archives export on Windows again.** msb 0.6.7/0.6.8 fail every
  `snapshot save` there with `Access is denied. (os error 5)`: the finished archive is
  fsynced through a read-only handle one step before the final rename. When exactly
  that failure occurs with exactly one finished staging file beside the destination,
  the backend completes the rename itself. Transparent, Windows-only, and
  self-disabling once msb fixes the fsync.

- **Container boot rides out msb's transient `install operation in progress` refusal**
  by polling for up to 30 seconds instead of failing on the first attempt.

- The Cassandra module's `GPG_KEYS` override remains required: 0.6.8 still aborts
  before the VM starts on any image whose baked environment contains a tab, verified
  directly against this release.

## [0.6.0] - 2026-07-28

### Upgrading from 0.5.0

Two changes affect existing code.

**Modules no longer pin an image version.** `RedisContainer()` previously booted
`redis:8.6-alpine`; it now boots `redis:latest`. Your tests will run whatever version
upstream currently publishes, which is the point — the version tracks the image's own
releases rather than this library's. To keep a specific version, name it:
`RedisContainer("redis:8.6-alpine")`. Redis, Valkey, PostgreSQL, and Memcached
additionally move from an Alpine variant to the Debian-based `latest`: functionally
equivalent, noticeably larger to pull.

**`ElasticsearchContainer` has no no-arg constructor.** Elastic publishes no floating
tag — `elasticsearch:latest`, `:9`, and `:8` are all `404` on Docker Hub — so an
explicit version is required and there is nothing this module could pick on your
behalf: `ElasticsearchContainer("elasticsearch:9.4.4")`.

An explicitly supplied image is also now checked against the repository the module
understands, so passing an unrelated image fails immediately with
`IncompatibleImageException` instead of timing out against the wrong server. If the
image really is a drop-in replacement, say so:
`DockerImageName.parse("mycorp/pg-hardened:16").asCompatibleSubstituteFor("postgres")`.

### Added

- **`DockerImageName`** (`dev.rightsize.core.image`) — a parsed Docker image reference
  (registry, repository, tag, digest), built via `DockerImageName.parse(String)`. Module
  constructors that accept an explicit image now check its repository against the one the
  module understands before any port, wait-strategy, or backend work runs, failing fast with
  the new typed `IncompatibleImageException` on a mismatch rather than degrading into a bare
  wait-strategy timeout. `DockerImageName.asCompatibleSubstituteFor(String)` is the escape
  hatch for a private mirror, a hardened rebuild, or a rename. Registry-host stripping follows
  the Docker convention: the first path segment is a registry only if it contains a `.` or a
  `:`, or is exactly `localhost`.
- **`ElasticsearchContainer`** — a single-node Elasticsearch container. Elastic publishes no
  floating tag for this image (`elasticsearch:latest`/`:9`/`:8` are all `404` on Docker Hub),
  so this module has no no-arg constructor — an explicit image is required. Readiness checks
  plain connectivity rather than cluster health, since a single node's health stays `yellow`
  forever (no peer to place replica shards on).
- **`QdrantContainer`** — a single-node Qdrant vector database container, defaulting to
  `qdrant/qdrant:latest`. Readiness is Qdrant's own `/readyz` probe, which answered on the
  first poll in direct verification; no memory limit is needed.

### Changed

- **Every one of the 21 pre-existing modules now defaults to a floating image reference**
  instead of a pinned version, and checks any explicitly supplied image against the
  repository it understands via `DockerImageName` (see above). Most float to
  `<repository>:latest`; `RabbitMQContainer` floats to `rabbitmq:management` instead, since
  plain `latest` lacks the management plugin the module is built around. Redis, Valkey,
  PostgreSQL, and Memcached move from a pinned Alpine variant to the Debian-based `latest`.
  No env var, port, wait strategy, memory limit, or command changed — each module's own
  KDoc/docs page states which pinned version its readiness signal, memory floor, and timing
  facts were verified against.

### Fixed

- **An `exec` issued immediately after `start()` could fail to reach the guest.** A
  sandbox reports `Running` before the in-guest agent has created the endpoint `exec`
  connects to; the gap is invisible whenever a wait strategy runs first, which is every
  module, but a caller that starts and execs at once could lose the race — reliably so on
  Windows, where the endpoint is a named pipe. `exec` now retries on that one signature.
  A guest command's own non-zero exit, and any agent error raised after connecting, still
  return on the first attempt.
- **`MongoDBContainer`'s replica-set budget is now 180s**, up from 60s. `rs.initiate`
  was observed failing at exactly the 60s mark on a loaded Windows CI runner against the
  floating default, matching the budget MySQL and ClickHouse already carry.

## [0.5.0] - 2026-07-25

### Added

- **`ValkeyContainer`** — a single-node Valkey container, the Redis-protocol-compatible
  fork. Readiness is anchored on Valkey's own `Ready to accept connections` log line, and
  `uri` returns a `redis://` URI because that is the scheme every Redis-protocol client
  parses.
- **`MinIOContainer`** — a single-node MinIO server, S3-compatible object storage. The
  image needs an explicit `server /data --console-address :9001` command, which this module
  always sets; readiness is MinIO's own `/minio/health/live` probe on the S3 API port.
  Defaults to a `testuser`/`testpassword` root pair, since MinIO rejects a root password
  shorter than eight characters.
- **`CassandraContainer`** — a single-node Apache Cassandra, ready-checked on its
  `Starting listening for CQL clients` log line. The module overrides the image's baked
  `GPG_KEYS` value, which contains a tab: the microsandbox backend aborts before the VM
  starts on any image whose baked environment carries one. `GPG_KEYS` is consumed only at
  image-build time, so the override has no effect on the running server.

## [0.4.0] - 2026-07-18

### Added

- **Checkpoint export/import (portable archives).** `Checkpoint.exportTo(path)`/`Checkpoint.importFrom(path)`
  package a checkpoint into a single portable tar (pinned `checkpoint.json` metadata plus the
  backend's own artifact — a docker `save` tar or an msb `snapshot export` `.tar.zst`) so it can
  ride along in a CI cache or move between machines running the same backend, instead of being
  re-seeded from scratch on every runner. `exportTo` requires the active backend to match the
  checkpoint's own and the artifact to still exist, both checked before any backend or filesystem
  work; `importFrom` validates the archive (format version, name grammar, backend match) before
  any backend call, then re-registers a NAMED archive with the same replace semantics
  `checkpoint(name)` uses. The image itself is never bundled — the destination pulls it on the
  restored container's first boot. `SandboxBackend.exportCheckpoint`/`importCheckpoint` are the
  new backend-side SPI primitives; on microsandbox, import mints a fresh digest-shaped effective
  ref (the original snapshot name is never preserved), while docker's `load` round-trips the
  original tag unchanged. See [Checkpoint / Restore](https://ngriaznov.github.io/rightsize-kotlin/checkpoints/#moving-checkpoints-between-machines).

## [0.3.0] - 2026-07-16

### Added

- **Runtime file copy.** `GenericContainer.copyFileToContainer`/`copyContentToContainer`/
  `copyFileFromContainer` move a file, a directory, or in-memory content into or out of an
  already-**running** container — distinct from the existing start-time
  `withCopyFileToContainer` mount. Both directions require the container to be running and the
  container-side path to be absolute (typed errors, checked before any backend call), and both
  automatically create the destination's parent directory (in the guest via `exec`, on the host
  via the stdlib) so callers never pre-create it. Directory-vs-file source and "copy into a
  nonexistent destination" naming follow `docker cp`/`msb copy`'s own `cp -r`-style semantics on
  both backends; docker shells out to the `docker` CLI's own `cp` (already a hard dependency via
  the reaper's watchdog commands) rather than hand-rolling tar encode/decode against the daemon
  API, and microsandbox uses `msb copy`. See [Copying Files](https://ngriaznov.github.io/rightsize-kotlin/copy/).
- **Checkpoint / restore, including named/persistent checkpoints.** `checkpoint()`/`fromCheckpoint`
  work on both backends: docker commits the running container to an image, microsandbox drives a
  disk snapshot (`msb stop` → `msb snapshot create` → `msb rm` → a fresh attached
  `msb run --snapshot <ref>` under the same name/ports/env/memory limit) — so
  `capabilities.checkpoint` is `true` on both, and `capabilities.checkpointRestartsWorkload`
  (`true` on microsandbox, whose snapshot cycle restarts the workload; `false` on docker, which
  leaves the container undisturbed) governs whether `checkpoint()` re-applies the container's own
  wait strategy before returning. `Checkpoint` carries `ref` (backend-shaped: a docker image tag
  or an msb snapshot name), `backend` (the backend that created it), and `spec` (the source
  container's env/command/exposed ports/memory limit) — restoring under a different active
  backend than the creator throws `CheckpointBackendMismatchException` before any backend call,
  naming both backends and the `RIGHTSIZE_BACKEND=<creator>` remedy. `SandboxBackend.createCheckpoint`/
  `removeCheckpoint` are the backend-side primitives (docker `rmi`/msb `snapshot rm` for removal);
  checkpoints are not auto-reaped.
  Passing a name to `checkpoint("seeded-db")` instead makes it durable and rediscoverable from any
  later process: the ref is derived from the name (replacing the random hex), a registry entry is
  written under the rightsize cache directory only after the backend checkpoint succeeds, and
  `Checkpoint.find(name)`/`list()`/`remove(name)` are the cross-process lookup/cleanup API —
  `find` probes the artifact via the new `SandboxBackend.hasCheckpoint` SPI method and resolves a
  stale entry (backend-side artifact gone) to absent, cleaning it up along the way. Re-checkpointing
  an existing name replaces it: the old artifact is best-effort removed before the new one is
  captured, and the registry entry is rewritten only once that capture succeeds. Names must match
  `^[a-z0-9][a-z0-9-]{0,40}$`, checked before any backend call. See
  [Checkpoint / Restore](https://ngriaznov.github.io/rightsize-kotlin/checkpoints/).

### Fixed

- **`MySQLContainer` readiness gets a 180-second budget** (was 120), the same treatment
  `ClickHouseContainer` already got. A loaded Windows CI runner was observed still short of
  ready at 123 seconds — past the previous ceiling. The budget is a deadline, not a wait —
  readiness returns the moment the real server's `ready for connections` line appears.
## [0.2.0] - 2026-07-12

### Added

- **Orphan reaping.** A crashed or `SIGKILL`ed process (no clean `stop()`, no shutdown hook)
  used to leak its sandboxes; now every process writes a small ownership ledger
  (`runs/<run-id>.{json,sandboxes,networks}`) under the rightsize cache dir before its first
  sandbox, and two layers reap a dead run's leftovers: an always-on init-time sweep (runs once
  per process, right after a backend resolves) and an optional per-run watchdog process that
  reacts within seconds of the crash instead of waiting for the next run. Controlled by the new
  `RIGHTSIZE_REAPER` variable (`on` default / `sweep` / `off`). See [Orphan
  Reaping](https://ngriaznov.github.io/rightsize-kotlin/reaping/) for the full mechanism.
- **`SandboxBackend.removeByName`**, a new SPI method reaping uses to remove a sandbox known
  only by name (not a live handle) — both backends implement it; the docker backend gets a
  cross-run orphan sweep for the first time (previously msb-only).
- **`ContainerSpec.keepAlive`**, a new field marking a sandbox as a reuse container. Every
  own-run cleanup path a backend runs consults it: msb never adds a `keepAlive` sandbox to its
  `startedNames` set (so neither the constructor shutdown hook nor `close()` reaps it), and
  docker labels a `keepAlive` container `dev.rightsize.reuse=<12hex>` instead of the run-id
  label at create time (so `close()`'s run-id label filter never matches it). The reaping
  ledger already excluded it.
- **Container reuse.** `GenericContainer.withReuse()` marks a container to survive `stop()`
  and process exit instead of being torn down — the next equivalent container (this process
  or a later one) adopts the still-running sandbox instead of booting fresh. Gated by a double
  opt-in: `withReuse()` alone does nothing unless `RIGHTSIZE_REUSE=true`/`1` is also set, so a
  fixture written with `withReuse()` never silently leaks a sandbox in an environment that
  didn't opt in. Identity is a SHA-256 over a canonical, cross-language-stable rendering of the
  container's image/env/command/exposed ports/memory limit/mounted file contents, named
  `rz-reuse-<12hex>` and tracked in a `<cache-dir>/reuse/<hash>.json` registry. Reuse and a
  custom `withNetwork()` are mutually exclusive (a typed error at `start()`) — reuse identity
  doesn't cover cross-container topology. See [Container
  Reuse](https://ngriaznov.github.io/rightsize-kotlin/reuse/) for the full mechanism.
- **`SandboxBackend.findRunning`**, a new SPI method reuse's adopt path uses to verify a
  registry-recorded sandbox is actually running before trusting it, given only its name.
- **Failure diagnostics.** `Diagnostics.report()` describes every currently-live container —
  name, image, state, host, mapped ports, and its last 50 log lines — as a plain string; a
  failing `logs` call degrades to `logs: unavailable (<reason>)` instead of throwing. The
  `@Sandboxed` extension now prints the report to `System.err` automatically, exactly once per
  failed test. See [Failure
  Diagnostics](https://ngriaznov.github.io/rightsize-kotlin/diagnostics/).
- **Isolation requirement.** `SandboxBackend.capabilities` exposes `hardwareIsolated` per
  backend (microsandbox: `true`, each sandbox its own microVM; docker: `false`, shared host
  kernel). `GenericContainer.withRequireIsolation()` makes `start()` throw
  `IsolationRequiredException` — before any create/network work, so no sandbox is created — when
  the active backend doesn't provide it. See [Isolation
  Requirement](https://ngriaznov.github.io/rightsize-kotlin/isolation/).
- **Checkpoint / restore.** `GenericContainer.checkpoint()` captures a running container's
  filesystem as a new image (`rightsize/checkpoint:<12-hex>`, random per call) via the new
  `SandboxBackend.commitToImage` SPI method; `GenericContainer.fromCheckpoint(cp)` boots a
  normal container from it, its env/command/exposed ports/memory limit defaulted from the
  checkpoint's spec and overridable with the usual `withX` builders. A restored container is
  ordinary in every respect — fresh host ports, normal reaping ledger entry, normal `stop()`.
  Implemented on the docker backend via the engine's commit endpoint
  (`capabilities.checkpoint = true`); microsandbox has no upstream snapshot primitive yet
  (`capabilities.checkpoint = false`) — `checkpoint()` throws `CheckpointUnsupportedException`
  before any backend call. This is a filesystem capture, not a memory snapshot: a restored
  container's processes restart from scratch. See [Checkpoint /
  Restore](https://ngriaznov.github.io/rightsize-kotlin/checkpoints/).
- **Cross-language parity, published.** The claim behind the five features above — that the
  same container spec produces the same observable behavior in rightsize's Kotlin, Rust, and
  Node libraries, on both backends — is now a documented artifact with a full behavior-area
  table (lifecycle, port mapping, env/command, copy-in, exec, logs/follow, wait strategies,
  networks/aliases, boot-failure retries, reaping, reuse identity, capabilities, isolation and
  checkpoint gating, diagnostics format) and a pinned cross-language identity-hash vector. See
  [Cross-Language Parity](https://ngriaznov.github.io/rightsize-kotlin/parity/).

### Changed

- **The old msb-only orphan sweep is gone.** `MsbBackendProvider.create()` used to scan `msb ls`
  once per process and best-effort-remove any `rz-*` name not belonging to the current run —
  liveness-blind (it could remove a sandbox from a *concurrent*, still-running process), msb-only,
  and only ever caught leftovers already visible in `msb ls` at process start. The new
  ledger-based sweep above replaces it, judges liveness properly (PID + start-time match), and
  works identically on both backends.

### Fixed

- **Reuse's fresh-create path retries on a host-port bind conflict**, same as the ordinary
  create path (`PORT_BIND_ATTEMPTS = 5`). It previously gave up after a single attempt, so a
  reuse container could fail to start on the same transient host-port race an ordinary
  container already retries through.

## [0.1.2] - 2026-07-09

### Changed

- **Pinned microsandbox runtime bumped from 0.6.3 to 0.6.6.** The provisioner
  downloads and SHA-256-verifies the new release on first use (existing
  `0.6.3` caches are left in place and simply stop being used). The full
  integration matrix passes unchanged on both backends against 0.6.6, and the
  backend behaviors the msb backend compensates for were re-verified as still
  present: detached `msb run` still never starts the image ENTRYPOINT, and
  `msb logs -f` still never exits after its sandbox stops.

## [0.1.1] - 2026-07-06

### Fixed

- **The default readiness budget is 120 seconds** (was 60). Three modules in a
  row (MySQL, ClickHouse, Redpanda) were observed overrunning a 60-second
  ceiling on loaded CI runners while booting normally. The budget is a
  deadline, not a wait — `start()` still returns the moment the readiness
  signal fires — so the larger default costs nothing on the happy path and
  only delays the failure verdict when a container is genuinely broken.
  `withStartupTimeout` overrides it as before.
- **`ClickHouseContainer` readiness gets a 180-second budget.** The entrypoint
  runs a second server pass for user/database provisioning before the HTTP
  interface opens, and a loaded Windows CI runner was observed still in early
  config processing at the previous 120-second ceiling. The budget is a
  deadline, not a wait — readiness returns the moment `/ping` answers.
- **The microsandbox backend retries a boot that hit msb's state-database
  error** (`error: database error: ...`). Every msb invocation runs schema
  migrations against its shared SQLite state database on startup, and two
  concurrent invocations can race them — the loser exits before doing any
  work, with whatever wording matches the statement it lost on (three shapes
  observed: `index ... already exists`, `duplicate column name: ...`, and
  `UNIQUE constraint failed: seaql_migrations.version`). A boot is never
  inherently alone (the attached `msb run` races the backend's own state
  polling), so this can fire even under fully serialized tests. The race is
  transient by construction — the winner's migration commits and later
  invocations find the schema in place — so a boot failing with msb's
  state-database framing is retried exactly once after a short delay; a
  second failure propagates with both attempts' output.

## [0.1.0] - 2026-07-06

Initial public release.

### Added

- **Examples** (`examples`): a Gradle subproject (not published) with three runnable
  examples — a plain-API Redis quickstart (`./gradlew :examples:runRedis`), a JUnit
  `@Sandboxed` PostgreSQL test over plain JDBC (`./gradlew :examples:test`), and a
  two-container `Network` example with a consumer reaching a WireMock stub by alias
  (`./gradlew :examples:runNetwork`). All three run on either backend via
  `RIGHTSIZE_BACKEND`.
- **Core engine** (`core`): a Testcontainers-shaped API — `GenericContainer`,
  `Network`, `Wait` (with `forListeningPort`/`forHttp`/`forLogMessage`),
  `MountableFile`, `Startables`, and the `@Sandboxed`/`@Container` JUnit 5
  extension — plus the `SandboxBackend`/`BackendProvider` SPI that lets
  alternative runtimes plug in via `ServiceLoader`.
- **Docker backend** (`backend-docker`): a `SandboxBackend` implementation on
  `docker-java`, serving as the correctness oracle for the microVM backend and
  the fallback runtime on platforms microVMs can't reach.
- **microsandbox backend** (`backend-microsandbox`): a `SandboxBackend`
  implementation on [microsandbox](https://github.com/superradcompany/microsandbox)
  (`msb`), including self-provisioning runtime download/install
  (SHA-256-verified, cached under `~/.cache/rightsize/`), attached-mode
  container supervision, pre-allocated ports for brokers that bake in their
  advertised listeners, and exec-tunnel network-alias emulation for
  cross-container connectivity.
- **Modules** (`modules`): preconfigured containers with sensible waits and
  connection helpers — `RedisContainer`, `ArangoContainer`, `MemcachedContainer`,
  `MongoDBContainer` (single-node replica set, auto-initiated),
  `PostgreSQLContainer` (`jdbcUrl`; `withUsername`/`withPassword`/`withDatabase`),
  `MySQLContainer` (`jdbcUrl`; `withUsername`/`withPassword`/`withDatabase`;
  readiness pinned to the real server's `port: 3306` log line, not the temp-boot
  or X-Plugin lines), `PinotContainer` (`controllerUrl`/`brokerUrl`; single-container
  QuickStart cluster; `withMemoryLimit(4096)` default, measured against the image's
  own `-Xmx4G` heap request), `RedpandaContainer`, `KafkaContainer` (KRaft single node),
  `SpringCloudConfigContainer`, `RabbitMQContainer` (`amqpUrl`/`managementUrl`; management
  plugin enabled), `MariaDBContainer` (`jdbcUrl`; readiness pinned to the real server's
  `port: 3306` log line, following `MySQLContainer`'s precedent), `WireMockContainer`
  (`baseUrl`/`adminUrl`; health-checked on `/__admin/health`), `ClickHouseContainer`
  (`httpUrl`; HTTP-interface query helpers, health-checked on `/ping`), and
  `KeycloakContainer` (`authServerUrl`/`managementUrl`; `KC_BOOTSTRAP_ADMIN_USERNAME`/
  `PASSWORD` — the 26.x env names, not the legacy `KEYCLOAK_ADMIN`; health-checked on
  `/health` on the **management port 9000**, not 8080; `withMemoryLimit(1024)` default), and
  `Neo4jContainer` (`httpUrl`/`boltUrl`; HTTP Cypher transaction endpoint, no bolt driver
  dependency needed; readiness pinned to the real server's `Started.` log line;
  `withMemoryLimit(1024)` default — the image refuses to start under msb's default microVM
  RAM budget with an explicit memory-configuration error, not an OOM kill), and
  `FlociContainer` ([floci.io](https://floci.io) cloud emulators; `aws()`/`azure()`/`gcp()`
  factory functions presetting image and port; `endpointUrl`; health-checked on `/health` —
  the AWS variant's LocalStack-compatible `/_localstack/health` does not carry over to the
  Azure/GCP variants, but plain `/health` answers `200` on all three; unsigned REST, no AWS
  SDK dependency needed for the S3-shaped surface; tiny native-Quarkus images, no memory
  override needed on either backend), and `FlinkContainer` (`restUrl`; `withTaskManager()`
  adds a companion TaskManager on a shared network for a real session cluster with task
  slots — **docker only**: throws `UnsupportedByBackendException` on the microsandbox
  backend, because msb's network-link emulation requires `nc`/busybox inside the consumer
  image and the official Flink image has neither; a bare JobManager, `REST /overview` only,
  is fully supported on both backends; `withMemoryLimit(1024)` default on both roles).
- **BOM** (`bom`): a `java-platform` module aligning versions across the four
  published artifacts.
- **Automatic backend selection**: `RIGHTSIZE_BACKEND` env var to force a
  backend; otherwise microsandbox on macOS Apple Silicon, Linux with
  `/dev/kvm`, or Windows with Windows Hypervisor Platform, falling back to
  Docker.
- **CI**: a GitHub Actions matrix covering the unit suite plus integration tests
  on Linux (KVM) and Windows (`windows-2025`, WHP), and a Docker-only fallback
  job; macOS (Apple Silicon) has no hosted-runner lane (GitHub's Apple Silicon
  runners don't support nested virtualization) and is verified on real
  hardware instead.
- **JaCoCo coverage floor** on `core` (80% line / 70% branch), gating `check`.
- Packaging/OSS groundwork: Apache-2.0 `LICENSE` + `NOTICE`, Maven publishing POM
  metadata, `.editorconfig`, `.github/CONTRIBUTING.md`, `.github/RELEASING.md`.
- **Memory limit knob**: `GenericContainer.withMemoryLimit(megabytes)`, mapped to msb's
  `-m ${mb}M` and Docker's `HostConfig.memory`; `SpringCloudConfigContainer` now defaults to
  1024M so its Paketo-buildpack JVM image boots under microsandbox's default microVM sizing.

### Changed

- **Documentation moved from `docs-site/` to `docs/`.** `mkdocs.yml`'s `docs_dir`
  and `edit_uri` were updated to match; no doc content or URL paths changed.
- **Pinned microsandbox runtime bumped from 0.6.2 to 0.6.3.** The provisioner
  downloads and SHA-256-verifies the new release on first use (existing `0.6.2`
  caches are left in place and simply stop being used). The full integration
  matrix passes unchanged on both backends against 0.6.3, and the backend
  behaviors the msb backend compensates for were re-verified as still present:
  detached `msb run` still never starts the image ENTRYPOINT, and `msb logs -f`
  still never exits after its sandbox stops.
- **Native Windows support for the microsandbox backend** (x86_64/arm64), gated
  on Windows Hypervisor Platform: two new `Platform` rows carrying the
  `msb-windows-*.exe`/`libkrunfw-windows-*.dll` release assets; the provisioner
  installs a platform-derived binary name (`msb.exe` on Windows, suffixless
  `msb` elsewhere) and gates install-completeness on a plain file-exists check
  on Windows rather than the POSIX executable bit; the default cache root on
  Windows is `%LOCALAPPDATA%\rightsize` (`RIGHTSIZE_CACHE_DIR` still overrides
  it everywhere). `MsbBackendProvider` attempts microsandbox on any detected
  Windows platform and surfaces a WHP-specific `unsupportedReason` (pointing at
  `msb doctor --fix`) if boot fails, rather than probing before the fact — CI
  (`msb-windows`, `windows-2025`) confirms this is safe because GitHub's hosted
  Windows runners ship with WHP already enabled. Attached-mode `msb run` on
  Windows does not relay the guest's stdout to the parent process (confirmed
  empirically); the backend already sources workload logs from `msb logs`
  exclusively, which was the only channel that ever worked correctly for that
  purpose on Windows. Process teardown documented, not changed: the JVM has no
  POSIX signals on Windows, so `destroy()`/`destroyForcibly()` both resolve to
  `TerminateProcess`, which is safe here because the actual graceful shutdown
  is the `msb stop`/`msb rm` invocation that already precedes killing the
  attached child on every platform. One more msb-Windows gap is compensated
  rather than surfaced: `msb logs -f` on Windows stays alive but never relays
  lines while the sandbox runs, so `followOutput` there polls fresh `msb logs`
  snapshots instead of holding a `logs -f` pipe — a failed msb invocation
  reads as no-signal, and the terminal tail is delivered exactly once after
  the sandbox stops, including a final line with no trailing newline. The
  full contract suite runs un-gated on Windows.

### Fixed

- **The microsandbox backend self-heals msb's image-cache race.** Concurrent
  pulls of images sharing base layers can corrupt msb's image cache — the
  losing pull reads a layer tarball the winner's cleanup already deleted, and
  every later boot of that image fails with `cache error at
  .../layers/<sha>.tar.gz: No such file or directory`. A boot failing with
  that signature now removes the affected image from msb's cache
  (`msb image remove`, scoped to the one reference) and retries the boot
  exactly once; any other failure, or a second failure after the heal,
  propagates unchanged.

[Unreleased]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.11...HEAD
[0.7.11]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.10...v0.7.11
[0.7.10]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.9...v0.7.10
[0.7.9]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.8...v0.7.9
[0.7.8]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.7...v0.7.8
[0.7.7]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.6...v0.7.7
[0.7.6]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.5...v0.7.6
[0.7.5]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.4...v0.7.5
[0.7.4]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.3...v0.7.4
[0.7.3]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.2...v0.7.3
[0.7.2]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.1...v0.7.2
[0.7.1]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.6.2...v0.7.0
[0.6.2]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.6.1...v0.6.2
[0.6.1]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.6.0...v0.6.1
[0.6.1]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/ngriaznov/rightsize-kotlin/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ngriaznov/rightsize-kotlin/releases/tag/v0.1.0
