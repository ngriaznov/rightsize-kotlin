plugins {
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(testFixtures(project(":core")))
}

// MsbCliBackend.importCheckpoint resolves CacheDir.resolve() to derive the checkpoints
// directory `msb snapshot load --dest` writes under. Pin it to a build-local dir here, the
// same rationale (and the same env var) core/build.gradle.kts already pins its own `test` task
// to, so a fake-msb-binary unit test never touches the developer's real `~/.cache/rightsize`.
tasks.named<Test>("test") {
    environment("RIGHTSIZE_CACHE_DIR", layout.buildDirectory.dir("test-cache/rightsize").get().asFile.absolutePath)
}
