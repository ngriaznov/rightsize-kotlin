plugins {
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(testFixtures(project(":core")))
}

// MsbCliBackend.importCheckpoint resolves CacheDir.resolve() to derive the checkpoints
// directory `msb snapshot load --dest` writes under, and MsbCliBackend.createCheckpoint's
// fresh-name reboot now calls the real Reaper singleton directly (see its own doc) to append the
// fresh name to the run ledger before the restore attempt. Pin both env vars to build-local/inert
// values here, the same rationale core/build.gradle.kts already pins its own `test` task with:
// RIGHTSIZE_CACHE_DIR keeps a fake-msb-binary unit test off the developer's real
// `~/.cache/rightsize`, and RIGHTSIZE_REAPER=sweep keeps the ledger writes (needed to red-proof
// the append-before-restore ordering) while skipping ON's detached watchdog-process spawn, which
// a unit test must never trigger.
tasks.named<Test>("test") {
    environment("RIGHTSIZE_CACHE_DIR", layout.buildDirectory.dir("test-cache/rightsize").get().asFile.absolutePath)
    environment("RIGHTSIZE_REAPER", "sweep")
}
