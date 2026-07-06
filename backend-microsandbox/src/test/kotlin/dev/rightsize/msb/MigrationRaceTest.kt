package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MigrationRaceTest {

    @Test fun `matches the captured msb error verbatim`() {
        // Captured verbatim from a real msb 0.6.3 Windows binary: the spawned `msb run`
        // lost the startup-migration race against a concurrent msb invocation (see the
        // classifier's doc).
        assertTrue(isMsbMigrationRace(
            "error: database error: Execution Error: error returned from database: " +
                "(code: 1) index idx_manifest_layers_unique already exists"))
    }

    @Test fun `matches the unique-constraint shape of the same race`() {
        // The other observed loser's message: the migrations bookkeeping row itself,
        // rather than a migration's DDL statement, loses the race.
        assertTrue(isMsbMigrationRace(
            "error: database error: Execution Error: error returned from database: " +
                "UNIQUE constraint failed: seaql_migrations.version"))
    }

    @Test fun `negative cases do not match`() {
        assertFalse(isMsbMigrationRace(""))
        assertFalse(isMsbMigrationRace("error: database error: disk I/O error"))
        // A name conflict ("already exists" without database-error framing) is the
        // start-retry path's concern, not this classifier's.
        assertFalse(isMsbMigrationRace("error: sandbox 'rz-abc-1' already exists"))
        // The image-cache corruption signature belongs to its own classifier and heal.
        assertFalse(isMsbMigrationRace(
            "error: image error: cache error at /tmp/cache/layers/sha256_dead.tar.gz: " +
                "No such file or directory (os error 2)"))
    }
}
