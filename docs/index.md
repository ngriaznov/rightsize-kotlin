# rightsize

**Testcontainers-style integration testing on microVMs. No Docker required.**

rightsize runs your integration-test containers as hardware-isolated
[microsandbox](https://github.com/superradcompany/microsandbox) microVMs — with a
Testcontainers-shaped Kotlin API, a self-provisioning runtime (one Gradle dependency,
zero install steps), and a Docker fallback for platforms microVMs can't reach.

```kotlin
@Sandboxed
class OrderServiceTest {
    companion object {
        @JvmStatic @Container
        val redis = RedisContainer()          // boots a real microVM

        @JvmStatic @Container
        val kafka = RedpandaContainer()
    }

    @Test
    fun `orders flow end to end`() {
        val client = RedisClient.create(redis.uri)   // 127.0.0.1:<mapped port>
        val producer = KafkaProducer<String, String>(props(kafka.bootstrapServers))
        // ... your test
    }
}
```

If you know Testcontainers, you already know this API — that's deliberate.

## Why rightsize

Hardware-level isolation (a microVM per container), no Docker daemon or Desktop
license, and a runtime that installs itself on first use — at the same end-to-end
speed as Docker on a real Spring Boot suite (164 s vs 165 s, fourteen test classes,
same machine). The pitch isn't "faster than Docker"; it's "as fast as Docker, with
hardware isolation and nothing to install."

The full comparison table, benchmark methodology, and platform matrix live in the
[project README](https://github.com/ngriaznov/rightsize-kotlin#why) — this site doesn't
repeat them. In one line: Apple Silicon macs, Linux-with-KVM, and Windows-with-WHP get
microVMs; everything else falls back to Docker automatically, and tests run unchanged
on either backend. Backend-specific edges are covered in
[Backends](backends.md#backend-differences).

## Where to go next

- **New to rightsize?** Start with [Getting Started](getting-started.md) — dependency
  setup, your first `@Sandboxed` test, and what happens on first run.
- **Want to run something first?** [`examples/`](https://github.com/ngriaznov/rightsize-kotlin/tree/main/examples)
  in the repo has three runnable examples (plain API, `@Sandboxed` JUnit, multi-container
  networking) — see the project README's [Examples](https://github.com/ngriaznov/rightsize-kotlin#examples)
  section for the exact commands.
- **Need a specific container?** Jump to [Modules](modules/index.md) for the full
  catalog with copy-paste test examples.
- **Something not working?** Check [Troubleshooting](troubleshooting.md) first —
  it's built from real failures hit while building rightsize itself.

## Status

On Maven Central under the group `dev.rightsize`. See
[Getting Started](getting-started.md) for the coordinates.

## License

[Apache-2.0](https://github.com/ngriaznov/rightsize-kotlin/blob/main/LICENSE)
