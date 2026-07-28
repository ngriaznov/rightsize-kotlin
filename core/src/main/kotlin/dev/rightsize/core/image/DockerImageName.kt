package dev.rightsize.core.image

import dev.rightsize.core.IncompatibleImageException

/**
 * A parsed Docker image reference — registry, repository, tag, digest — used by module
 * constructors to check that an explicitly supplied image is compatible with what the module
 * expects, before any port, wait-strategy, or backend work runs. Build one with [parse]; a
 * module's own constructor calls [assertCompatibleWith] against the repository it understands
 * (e.g. `PostgreSQLContainer` expects `postgres`, `QdrantContainer` expects `qdrant/qdrant`).
 *
 * ```kotlin
 * PostgreSQLContainer("postgres:16")                                // checked, common case
 * PostgreSQLContainer(DockerImageName.parse("mycorp/pg-hardened:16")
 *     .asCompatibleSubstituteFor("postgres"))                       // explicit override
 * ```
 *
 * Registry-host stripping follows the Docker convention: the first path segment (before the
 * first `/`) is a registry host only when it contains a `.` or a `:`, or is exactly
 * `localhost` — so `quay.io/keycloak/keycloak` and `localhost/keycloak` strip down to the
 * repositories `keycloak/keycloak` and `keycloak`, while `floci/floci` (no dot, colon, or
 * `localhost` in `floci`) stays whole, both segments belonging to the repository.
 *
 * An image built this way, or passed in as a plain `String`, is always used verbatim as the
 * container's image — this type only ever checks compatibility; it never rewrites a tag or
 * substitutes an image on its own.
 */
class DockerImageName private constructor(
    /** The registry host, e.g. `quay.io` — `null` when the image names no registry (Docker Hub). */
    val registry: String?,
    /** The repository component with any registry host already stripped, e.g. `keycloak/keycloak`. */
    val repository: String,
    /** The tag, e.g. `16` — `null` when the image names none. */
    val tag: String?,
    /** The digest (without the leading `@`), e.g. `sha256:...` — `null` when the image names none. */
    val digest: String?,
    private val substituteForRepository: String?,
) {
    /** Renders back the exact reference this was built from — registry, repository, tag, and
     * digest in the usual `[registry/]repository[:tag][@digest]` shape. This is what's actually
     * passed to the backend as the image, unchanged by [asCompatibleSubstituteFor]. */
    override fun toString(): String = buildString {
        registry?.let { append(it).append('/') }
        append(repository)
        tag?.let { append(':').append(it) }
        digest?.let { append('@').append(it) }
    }

    /**
     * Declares this image a deliberate stand-in for [expectedRepository] — a private mirror, a
     * hardened rebuild, a rename — mirroring Testcontainers' escape hatch of the same name.
     * [assertCompatibleWith] then compares against [expectedRepository] instead of [repository];
     * [toString] is unaffected, so the image is still used exactly as given.
     */
    fun asCompatibleSubstituteFor(expectedRepository: String): DockerImageName =
        DockerImageName(registry, repository, tag, digest, expectedRepository)

    /**
     * Module-constructor helper: fails fast with [IncompatibleImageException] when this image's
     * repository — or, once [asCompatibleSubstituteFor] has been called, the repository it was
     * declared a substitute for — does not equal [expectedRepository]. Called from a module's
     * `init` block, before [dev.rightsize.GenericContainer.withExposedPorts]/`waitingFor`/`start`
     * ever runs, so a mismatched image fails with a clear error naming both repositories, never a
     * bare wait-strategy timeout against the wrong server.
     */
    fun assertCompatibleWith(expectedRepository: String) {
        val effective = substituteForRepository ?: repository
        if (effective != expectedRepository) {
            throw IncompatibleImageException(repository, expectedRepository)
        }
    }

    companion object {
        /**
         * Parses [fullImageName] per Docker's image reference grammar,
         * `[registry[:port]/]repository[:tag][@digest]`. A trailing `:tag` is told apart from a
         * registry's `:port` by position — the last `:` starts a tag only when it appears after
         * the last `/` (so `localhost:5000/repo:16` keeps its port and its tag straight, and
         * `localhost:5000/repo` with no tag keeps its port without inventing one). The first path
         * segment is a registry only if it contains a `.` or a `:` (see the class doc); otherwise
         * the whole remainder (e.g. `floci/floci`) is the repository.
         */
        fun parse(fullImageName: String): DockerImageName {
            require(fullImageName.isNotBlank()) { "image name must not be blank" }

            var remainder = fullImageName
            val atIndex = remainder.lastIndexOf('@')
            val digest = if (atIndex >= 0) remainder.substring(atIndex + 1) else null
            if (atIndex >= 0) remainder = remainder.substring(0, atIndex)

            val lastSlash = remainder.lastIndexOf('/')
            val lastColon = remainder.lastIndexOf(':')
            val tag: String?
            if (lastColon > lastSlash) {
                tag = remainder.substring(lastColon + 1)
                remainder = remainder.substring(0, lastColon)
            } else {
                tag = null
            }

            val firstSlash = remainder.indexOf('/')
            val registry: String?
            val repository: String
            if (firstSlash >= 0) {
                val firstSegment = remainder.substring(0, firstSlash)
                // `localhost` is a registry host despite carrying neither a dot nor a port —
                // Docker special-cases it, and without this `localhost/myimage` would parse as the
                // two-segment repository `localhost/myimage` and fail an otherwise valid
                // compatibility check.
                if (firstSegment.contains('.') || firstSegment.contains(':') || firstSegment == "localhost") {
                    registry = firstSegment
                    repository = remainder.substring(firstSlash + 1)
                } else {
                    registry = null
                    repository = remainder
                }
            } else {
                registry = null
                repository = remainder
            }
            require(repository.isNotBlank()) { "image name '$fullImageName' has no repository component" }

            return DockerImageName(registry, repository, tag, digest, null)
        }
    }
}
