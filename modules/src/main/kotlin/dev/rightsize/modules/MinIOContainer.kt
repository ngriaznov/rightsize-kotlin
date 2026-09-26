package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A single-node MinIO container, an S3-compatible object store. Exposes the S3 API (port 9000 —
 * what [endpointUrl] wraps) and the console (port 9001, exposed but not wrapped by a helper, the
 * same "exposed but unwrapped" treatment [ClickHouseContainer] gives its native-protocol port).
 *
 * ### Defaults to `pgsty/minio:latest` — this image's floating reference
 *
 * With no image given, this module tracks the image's `latest` tag rather than a version this
 * library pins, so the version moves with that image's releases instead of this library's release
 * cycle. Pass an image explicitly to pin it:
 * `MinIOContainer("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")`.
 *
 * The facts below were verified against `minio/minio:RELEASE.2025-09-07T16-13-09Z`. Readiness,
 * auth enforcement, and the `mc` round-trip were verified again by this module's integration
 * test against `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` (what `latest` pointed at) on msb
 * 0.7.3; the memory spike was not repeated.
 *
 * ### Why `pgsty/minio`
 *
 * MinIO no longer publishes public images. Docker Hub's `minio/minio` was removed, and as of
 * September 2026 `quay.io/minio/minio`, this module's previous default, refuses anonymous pulls
 * (HTTP 401). `pgsty/minio` is Pigsty's community build of MinIO from source, published on Docker
 * Hub for linux/amd64 and linux/arm64 with upstream's image layout: the same entrypoint and env
 * defaults, and the `mc` client bundled.
 *
 * [EXPECTED_REPOSITORY] stays `minio/minio`. Repository compatibility
 * ([dev.rightsize.core.image.DockerImageName.assertCompatibleWith]) is checked after stripping any
 * registry host, so `quay.io/minio/minio:<tag>` and a bare `minio/minio:<tag>` override (still
 * useful behind a mirror, or with the image cached) are accepted as before, and this module
 * accepts `pgsty/minio:<tag>` as a declared substitute for `minio/minio`. Any other repository is
 * rejected.
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
 * A verification spike ran `minio/minio` at 1024 MB with no issues. Whether any floor is needed at
 * all under this backend's default allocation is not yet established, so this module sets no
 * [withMemoryLimit] override; callers who hit memory pressure can call it themselves.
 */
class MinIOContainer(image: DockerImageName) : GenericContainer<MinIOContainer>(image.toString()) {
    /** Defaults to `pgsty/minio:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "pgsty/minio:latest") : this(DockerImageName.parse(image))

    private var usernameState = "testuser"
    private var passwordState = "testpassword"

    init {
        // pgsty/minio is this module's own default (see "Why pgsty/minio" in the class doc), so
        // it counts as a substitute for minio/minio without the caller declaring it.
        val checkedImage = if (image.repository == PGSTY_REPOSITORY) {
            image.asCompatibleSubstituteFor(EXPECTED_REPOSITORY)
        } else {
            image
        }
        checkedImage.assertCompatibleWith(EXPECTED_REPOSITORY)
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
        const val EXPECTED_REPOSITORY = "minio/minio"
        const val PGSTY_REPOSITORY = "pgsty/minio"
    }
}
