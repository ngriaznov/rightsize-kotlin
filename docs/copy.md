# Copying Files

`copyFileToContainer`, `copyContentToContainer`, and `copyFileFromContainer` move files into or
out of an already-**running** container — the Testcontainers-style runtime operations, distinct
from `withCopyFileToContainer`'s start-time mount.

## Runtime copy vs. start-time mount

`withCopyFileToContainer(file, guestPath)` is configured before `start()` and takes effect at
boot: a read-only-by-default host mount, present from the moment the container comes up. See
[Files & Memory](concepts/files-and-memory.md).

The three methods on this page are different: they act on a container that is already running,
any time after `start()`. Reach for them when the file to copy doesn't exist yet at boot time —
generated during the test, produced by an earlier step, or read back out after the workload has
written it.

| | `withCopyFileToContainer` | `copyFileToContainer` / `copyContentToContainer` / `copyFileFromContainer` |
|---|---|---|
| When | Configured before `start()` | Any time after `start()` |
| Container state | Not yet running | Running |
| Direction | Host → guest only | Both directions |
| Typical use | Seed data known ahead of time | Generated fixtures, extracting artifacts/debug dumps |

## API

```kotlin
val c = GenericContainer("alpine:3.19").withCommand("sleep", "120")
c.start()

// host file/dir -> container
c.copyFileToContainer(Path.of("/host/seed.sql"), "/seed/seed.sql")

// in-memory bytes or string -> container
c.copyContentToContainer("SELECT 1;", "/seed/probe.sql")
c.copyContentToContainer(byteArrayOf(1, 2, 3), "/seed/payload.bin")

// container file/dir -> host
c.copyFileFromContainer("/var/log/app.log", Path.of("/host/out/app.log"))
```

All three:

- **require the container to be running** — a never-started or stopped container throws
  `IllegalStateException` (the same "not running" error `execInContainer`/`getMappedPort` use),
  before any backend call;
- **require an absolute container path** — a relative one throws
  `NonAbsoluteContainerPathException` before any backend call, since both `docker cp` and
  `msb copy` require a `NAME:/abs/path` shape;
- **create missing parent directories automatically** — a copy-in creates the destination's
  parent directory in the guest (`mkdir -p`) first; a copy-out creates the host destination's
  parent directory first. Neither side needs to be pre-created by the caller;
- **raise `ContainerCopyException` on a failed transfer** — carrying the underlying tool's
  stderr (a missing source file in the guest, a permission error, etc.). A failed copy is never a
  silent success.

## Directory semantics

Each method accepts a file **or** a directory as its source — there's no separate "directory"
variant. Semantics follow `docker cp`/`msb copy` themselves: copying a directory to a destination
that doesn't yet exist creates that destination as a copy of the source directory's *contents*,
not as a subdirectory named after the source:

```kotlin
// hostDir contains: hostDir/a.txt, hostDir/nested/b.txt
c.copyFileToContainer(hostDir, "/dst")
// guest now has: /dst/a.txt, /dst/nested/b.txt — NOT /dst/hostDir/a.txt
```

The same `cp -r`-style naming applies to copy-out.

## `copyContentToContainer`

The in-memory convenience over `copyFileToContainer`: writes `content` (a `ByteArray` or a
`String`, UTF-8 encoded) to a temp file — mode `0600` where the host filesystem supports POSIX
permissions — delegates to `copyFileToContainer`, then removes the temp file whether the copy
succeeded or not. There's no streaming protocol; for anything large enough that buffering the
whole payload in memory first is a problem, write it to a host file yourself and use
`copyFileToContainer` directly.

## Reuse caveat

Runtime copy is just an ordinary operation against a running sandbox — it works the same way on
a [reuse](reuse.md) container as on any other. It is **not** part of reuse identity: copying a
file into (or out of) a reused sandbox mutates its shared state, and that mutation is invisible
to the identity hash. If two equivalent containers are meant to adopt the same sandbox, don't
rely on a runtime copy to distinguish them — use `withCopyFileToContainer` (which the identity
hash *does* cover) if the file content should be part of what makes two containers "the same".
