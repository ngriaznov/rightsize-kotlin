package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MsbStateDbErrorTest {

    @Test fun `matches the captured migration-race shapes verbatim`() {
        // Both captured verbatim from a real msb 0.6.3 Windows binary: the spawned
        // `msb run` lost the startup-migration race against a concurrent msb
        // invocation — one race, different losing statements (see the classifier's doc).
        assertTrue(isMsbStateDbError(
            "error: database error: Execution Error: error returned from database: " +
                "(code: 1) index idx_manifest_layers_unique already exists"))
        assertTrue(isMsbStateDbError(
            "error: database error: Execution Error: error returned from database: " +
                "(code: 1) duplicate column name: kind"))
    }

    @Test fun `matches the unique-constraint shape of the same race`() {
        assertTrue(isMsbStateDbError(
            "error: database error: Execution Error: error returned from database: " +
                "UNIQUE constraint failed: seaql_migrations.version"))
    }

    @Test fun `matches any msb state-database failure, not just the known wordings`() {
        // The classifier keys on msb's own framing, not the SQLite message — chasing
        // individual wordings is how the third race shape slipped through. A one-shot
        // retry on a non-race database error is harmless: it costs a moment and then
        // propagates with both attempts' output.
        assertTrue(isMsbStateDbError("error: database error: disk I/O error"))
    }

    @Test fun `negative cases do not match`() {
        assertFalse(isMsbStateDbError(""))
        // A workload's stderr complaining about ITS database — no msb `error:` framing.
        assertFalse(isMsbStateDbError("app: database error: connection refused"))
        // A name conflict is the start-retry path's concern, not this classifier's.
        assertFalse(isMsbStateDbError("error: sandbox 'rz-abc-1' already exists"))
        // The image-cache corruption signature belongs to its own classifier and heal.
        assertFalse(isMsbStateDbError(
            "error: image error: cache error at /tmp/cache/layers/sha256_dead.tar.gz: " +
                "No such file or directory (os error 2)"))
    }
}
