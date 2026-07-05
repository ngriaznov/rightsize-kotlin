package dev.rightsize

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A file or classpath resource to be mounted into a container via
 * `GenericContainer.withCopyFileToContainer`.
 */
class MountableFile private constructor(val path: Path) {
    companion object {
        /** A file already on the host filesystem, resolved to an absolute path. */
        fun forHostPath(path: String) = MountableFile(Path.of(path).toAbsolutePath())

        /**
         * Resolves [resource] on the current thread's context classloader and copies it into a
         * fresh temp file so backends (which need a real host path to mount/copy) can use it.
         *
         * The temp file is registered with [java.io.File.deleteOnExit], which only fires at JVM
         * shutdown — fine for the short-lived test/build processes this is designed for, but note
         * that a long-running JVM repeatedly calling this will accumulate temp files until exit.
         */
        fun forClasspathResource(resource: String): MountableFile {
            val normalized = resource.removePrefix("/")
            val loader = Thread.currentThread().contextClassLoader
            val url = loader.getResource(normalized)
                ?: error(
                    "Classpath resource not found: '$normalized' on classloader $loader " +
                        "(original request: '$resource'). Verify the resource is on the classpath " +
                        "(e.g. under src/.../resources) and the path has no leading slash issues.",
                )
            // Resource paths are always '/'-separated regardless of host OS, so split on '/'
            // rather than Path.of(resource).fileName, which would use the platform separator.
            val fileName = normalized.substringAfterLast('/')
            val tmp = Files.createTempFile("rightsize-", "-$fileName")
            url.openStream().use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            tmp.toFile().deleteOnExit()
            return MountableFile(tmp)
        }
    }
}
