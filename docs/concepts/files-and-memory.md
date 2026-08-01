# Files & Memory

## `MountableFile`

`MountableFile` describes a file to mount into a container via
`GenericContainer.withCopyFileToContainer(file, guestPath)`. This is a **start-time** mount,
configured before `start()` — see [Copying Files](../copy.md) for the runtime equivalent
(`copyFileToContainer`/`copyContentToContainer`/`copyFileFromContainer`), which acts on an
already-running container in either direction. Build a `MountableFile` from either a host path or
a classpath resource:

```kotlin
val fromDisk = MountableFile.forHostPath("/absolute/or/relative/path/on/disk")
val fromClasspath = MountableFile.forClasspathResource("fixtures/seed-data.sql")

container.withCopyFileToContainer(fromClasspath, "/docker-entrypoint-initdb.d/seed.sql")
```

- `forHostPath` resolves the given path to an absolute path immediately; it doesn't
  check the file exists until mount time.
- `forClasspathResource` resolves the resource against the current thread's context
  classloader, copies it into a fresh temp file (backends need a real host path to
  mount, not a classpath URL), and registers that temp file with
  `File.deleteOnExit()`. That's fine for the short-lived test/build JVMs this is
  designed for — if your process is long-running and calls this repeatedly, be aware
  the temp files accumulate until the JVM actually exits.

### Read-only mounts

`FileMount.readOnly` (the flag underneath `withCopyFileToContainer`) defaults to
`false`: the mount is read-write, and it is a view of the host file rather than a copy
of it — the Docker backend binds the host path directly, the microsandbox backend
hard-links it into its staging directory — so a guest write reaches the host file
itself.

A mount constructed with `readOnly = true` is enforced on both backends as a
guest-side write block: an in-guest write fails with `Read-only file system`. Use it
whenever the host copy must not be modified — in particular when the container runs
code you don't trust (see [Isolation](../isolation.md#untrusted-code-guidance)).

## `withMemoryLimit(megabytes)`

Caps a container's guest memory. Maps to msb's `-m <megabytes>M` flag on the
microsandbox backend and to `HostConfig.memory` (in bytes) on the Docker backend.
Leave it unset (the default) and each backend applies its own default sizing instead.

```kotlin
GenericContainer("some-jvm-heavy-image:latest")
    .withMemoryLimit(1024)   // MB
```

### When you actually need this

microsandbox's default microVM has a small amount of guest-available RAM — roughly
450 MB in practice (the exact figure observed varies a little run to run, somewhere in
the ~443–454 MB range, but "~450 MB" is the number to plan around). Most images fit
comfortably under that. Two categories don't, and both are shipped modules that had to
raise the limit to boot at all:

**Spring Cloud Config Server.** The `hyness/spring-cloud-config-server` image is built
on a Paketo buildpack, and Paketo's memory calculator sizes the JVM's fixed regions
(heap + metaspace + thread stacks, computed ahead of time from the image's own
metadata) to around 688 MB — comfortably over the microVM default. `SpringCloudConfigContainer`
sets `withMemoryLimit(1024)` for exactly this reason; without it, the JVM fails to
launch under the microsandbox backend (it boots fine under Docker, whose containers
aren't memory-constrained by default, which is precisely why this class of bug is easy
to miss if you only ever test on Docker).

**Apache Pinot's QuickStart cluster.** `PinotContainer` runs a `QuickStart -type EMPTY`
process tree — ZooKeeper, controller, broker, server, and minion, four JVMs plus
ZooKeeper, all inside one container — and the image itself bakes in
`JAVA_OPTS=-Xms4G -Xmx4G` for the QuickStart driver JVM alone, before any of the four
sub-JVMs it spawns have taken anything. The original plan was `withMemoryLimit(2048)`
by analogy with the Config Server fix; that under-shot badly. Measured directly against
real boots:

| Memory limit | Result |
|---|---|
| 2048 MB | OOM-killed — timed out waiting for `/health`, reaped by the kernel |
| 2560 MB | OOM-killed |
| 3072 MB | Boots; `/health` returns 200 within ~15s — but `docker stats` settles at ~99% of the limit, and under that pressure the controller's Helix-backed schema/table-config RPCs intermittently time out even though `/health` reports 200 |
| **4096 MB** | Boots cleanly; `docker stats` settles at ~73–75%; schema POST succeeded on every attempt across a 60s repeated-POST probe |

`PinotContainer` ships with `withMemoryLimit(4096)` — the lowest round number that
leaves real headroom above the image's own 4 GiB heap request, not merely enough to
dodge the OOM killer outright. Verified stable on both backends. If you're
configuring memory limits for your own JVM-heavy image, treat "boots without getting
OOM-killed" as a necessary but not sufficient bar — leave headroom, the way this
module does, or you'll see the same kind of intermittent RPC timeout under memory
pressure that 3072 MB produced here.

### Images that don't need it

Most modules never touch `withMemoryLimit` at all — Redis, an Erlang VM like
RabbitMQ, single-process HTTP servers like WireMock or ClickHouse's server (not a JVM
process at all), and even MySQL/MariaDB's InnoDB default footprint all boot cleanly
under the ~450 MB default with no adjustment. Only reach for `withMemoryLimit` when
you actually observe a boot failure or OOM under the microsandbox backend — don't
apply it prophylactically to every module.

## `withDiskLimit(megabytes)` and `withTmpfsRoot(megabytes)`

Two more msb-only knobs control the container's writable root disk. Both are ignored on the
Docker backend — Docker runs without a root-disk ceiling and has no RAM-backed rootfs mode, so
either builder is a silent no-op there.

```kotlin
GenericContainer("some-image:latest")
    .withDiskLimit(2048)   // MB — caps the writable root disk
```

```kotlin
GenericContainer("some-image:latest")
    .withTmpfsRoot(512)    // MB — RAM-backed root disk instead of storage
```

**`withDiskLimit`** caps the writable root disk at the given size (msb's `--root-disk <mb>M`).
On an msb reboot the cap can only grow, never shrink.

**`withTmpfsRoot`** backs the root disk with RAM instead of disk storage (msb's
`--root-disk tmpfs:<mb>M`) — faster ephemeral containers, and nothing written to the rootfs
leaves a trace on the host disk afterward. It has to fit inside the guest's own memory: msb
defaults the guest to 512M when `withMemoryLimit` is unset, and setting both with a tmpfs size
larger than the memory limit throws `TmpfsRootExceedsMemoryException` at `start()`.

Both are mutually exclusive with each other — the root disk is either size-capped or RAM-backed,
never both — and combining them throws `RootDiskConflictException` at `start()`, before any
backend call.

### Constraints worth knowing before you reach for these

- **A tmpfs root cannot be checkpointed.** `checkpoint()` on a `withTmpfsRoot()` container throws
  `TmpfsRootCheckpointException` before touching anything — the root disk lives in guest memory,
  so there's nothing durable to snapshot. A refused named re-checkpoint leaves the existing
  checkpoint entirely intact. See [Checkpoint / Restore](../checkpoints.md).
- **Neither survives a `fromCheckpoint` restore.** msb rejects any root-disk setting
  (`withDiskLimit` or `withTmpfsRoot`) on a container restored via `fromCheckpoint` before boot —
  the snapshot already pins the root disk from whatever it was when the checkpoint was taken.
