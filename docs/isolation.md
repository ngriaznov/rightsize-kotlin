# Isolation Requirement

rightsize's two backends give different isolation guarantees, and the difference matters the
moment a container runs code you don't fully trust — a user-submitted script, a plugin, an
untrusted image pulled at test time. `withRequireIsolation()` lets a container demand the
stronger guarantee and fail fast instead of silently running under the weaker one.

## Guarantees

| | microsandbox | Docker (fallback) |
|---|---|---|
| Kernel | Its own — each sandbox is a full microVM | Shared with the host |
| Isolation boundary | Hardware virtualization (KVM/WHP) | Linux namespaces/cgroups |
| A container escape reaches | Only that microVM | The host kernel |
| `capabilities.hardwareIsolated` | `true` | `false` |

Docker's namespace/cgroup isolation is real and sufficient for the vast majority of testing —
running trusted images you built yourself against trusted test data. It is not a hardware
security boundary: a kernel exploit inside a Docker container can reach the host. microsandbox's
microVMs give each sandbox its own kernel, the same class of guarantee cloud providers rely on
for multi-tenant workloads.

## When to require isolation

Reach for `withRequireIsolation()` when a container will run something you did not write or
fully vet — grading a user's submitted code, sandboxing a plugin, executing an image pulled
from an untrusted registry at test time. For ordinary module containers (Redis, Postgres, the
image you built from your own Dockerfile) it's unnecessary overhead: you already trust the
image.

## API

```kotlin
val untrusted = GenericContainer("some/untrusted-image:latest")
    .withRequireIsolation()
untrusted.start()
```

`start()` checks `capabilities.hardwareIsolated` on the active backend before any create,
network, or port work — if it's `false`, `start()` throws `IsolationRequiredException` and no
sandbox is ever created:

```text
withRequireIsolation() requires a hardware-isolated backend, but the active backend is
'docker', which is not — set RIGHTSIZE_BACKEND=microsandbox to use the microsandbox backend, which
runs each sandbox in its own microVM
```

Force the microsandbox backend with `RIGHTSIZE_BACKEND=microsandbox` (see
[Backends](backends.md#backend-selection)) wherever isolation-requiring containers run.

## Untrusted-code guidance

`withRequireIsolation()` picks the right backend; it doesn't replace the rest of a defense-in-depth
setup around code you don't trust:

- **Cap memory.** `withMemoryLimit(megabytes)` bounds what the sandbox can consume — untrusted
  code shouldn't be able to exhaust host memory even inside a microVM.
- **No secrets in the environment.** `withEnv(...)` values are visible to whatever runs inside
  the container; never pass API keys, credentials, or other secrets into a container running
  untrusted code.
- **Prefer `withRequireIsolation()` over trusting an auto-selected backend.** Auto-selection
  picks microsandbox when available but silently falls back to Docker when it isn't (no KVM/WHP,
  unsupported host) — exactly the situation `withRequireIsolation()` is for: fail the start
  instead of running untrusted code under the weaker guarantee without anyone noticing.
- **Keep mounts read-only** (the default for `withCopyFileToContainer`) unless the untrusted
  code genuinely needs to write back to the host filesystem.
