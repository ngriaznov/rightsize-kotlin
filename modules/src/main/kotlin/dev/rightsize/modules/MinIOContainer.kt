package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

/**
 * A single-node MinIO container, an S3-compatible object store. Exposes the S3 API (port 9000 —
 * what [endpointUrl] wraps) and the console (port 9001, exposed but not wrapped by a helper, the
 * same "exposed but unwrapped" treatment [ClickHouseContainer] gives its native-protocol port).
 *
 * ### The default entrypoint does not serve — a command is required
 *
 * Booting this image with no command produces no listening S3 API at all; `server /data
 * --console-address :9001` is required to make it actually serve. This module sets that command
 * unconditionally — there is no bare-entrypoint mode worth exposing.
 *
 * ### Credentials default to `testuser`/`testpassword`, not the house `test`/`test` pair
 *
 * MinIO rejects a root password shorter than 8 characters at startup, so the two-character
 * `test`/`test` pair used by [ClickHouseContainer] and friends cannot be reused here. `testuser`/
 * `testpassword` is the shortest pair that both clears that floor and stays obviously a test
 * credential. Call [withUsername]/[withPassword] before [start] to override either.
 *
 * ### Readiness — protocol-aware, answers correctly on the first poll
 *
 * `Wait.forHttp("/minio/health/live").forPort(9000)` returns `200` as soon as the S3 API is
 * actually serving; verified directly against a real boot, where the first poll after the
 * container was up already returned `200` — no retry/backoff story to document here.
 *
 * ### Round-trip and auth verified with the bundled `mc` client, no SDK
 *
 * `mc mb` (make bucket) followed by `mc cp` (write) and `mc cat` (read back) against the
 * configured credentials returned the exact bytes written. `mc cp` uploads a file written into
 * the guest first, rather than piping bytes into `mc pipe` over stdin: an exec'd `mc pipe` under
 * this backend either dumps its goroutines and exits non-zero or hangs outright, both observed
 * directly, while `mc cp` needs no stdin and round-trips reliably. Separately, an anonymous
 * `GET /` against the S3 API returned `AccessDenied` rather than serving, confirming auth is
 * actually enforced and not merely configured. Both were verified through `exec` on the running
 * container using the `mc` binary the image already bundles — this module and its tests pull in
 * no S3 client dependency.
 *
 * ### Memory
 *
 * A verification spike ran this image at 1024 MB with no issues. Whether any floor is needed at
 * all under this backend's default allocation is not yet established, so this module sets no
 * [withMemoryLimit] override; callers who hit memory pressure can call it themselves.
 */
class MinIOContainer(image: String = "minio/minio:RELEASE.2025-09-07T16-13-09Z") :
    GenericContainer<MinIOContainer>(image) {
    private var usernameState = "testuser"
    private var passwordState = "testpassword"

    init {
        withExposedPorts(API_PORT, CONSOLE_PORT)
        withCommand("server", "/data", "--console-address", ":$CONSOLE_PORT")
        withEnv("MINIO_ROOT_USER", usernameState)
        withEnv("MINIO_ROOT_PASSWORD", passwordState)
        // Protocol-aware: answers 200 the moment the S3 API is serving, verified on the first
        // poll after boot with no restart/double-boot race to work around.
        waitingFor(Wait.forHttp("/minio/health/live").forPort(API_PORT))
    }

    /** Overrides `MINIO_ROOT_USER` (default `testuser`). */
    fun withUsername(username: String): MinIOContainer {
        usernameState = username
        return withEnv("MINIO_ROOT_USER", username)
    }

    /** Overrides `MINIO_ROOT_PASSWORD` (default `testpassword`). Must be at least 8 characters — MinIO rejects a shorter root password at startup. */
    fun withPassword(password: String): MinIOContainer {
        passwordState = password
        return withEnv("MINIO_ROOT_PASSWORD", password)
    }

    /** The configured root user (default `testuser`). */
    val username: String get() = usernameState
    /** The configured root password (default `testpassword`). */
    val password: String get() = passwordState

    /** The S3 API's base URI (port 9000) — point any S3-compatible client at this with [username]/[password]. */
    val endpointUrl: String get() = "http://$host:${getMappedPort(API_PORT)}"

    private companion object {
        const val API_PORT = 9000
        const val CONSOLE_PORT = 9001
    }
}
