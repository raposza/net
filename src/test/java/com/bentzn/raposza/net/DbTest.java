/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The schema against a real engine. An embedded database needs no server, so
 * what used to be deferred to a deployed environment is proved here.
 *
 * Author Claude/bentzn
 */
class DbTest {

    @TempDir
    Path dirTmp;


    @Test
    void schemaAppliesAndAppliesAgainWithoutComplaint() throws Exception {
        try (Connection conn = open()) {
            Db.schema(conn);
            Db.schema(conn);
            assertTrue(Db.isEmpty(conn));
            assertEquals(0L, count(conn, "source_observation"));
            assertEquals(0L, count(conn, "claim"));
        }
    }


    @Test
    void aSourceUpsertKeepsWhatTheCollectorOwns() throws Exception {
        try (Connection conn = open()) {
            Db.schema(conn);
            SourceDef def = new SourceDef("s1", "pub", "git", "https://example.invalid/r.git",
                    null, null, "OFFICIAL", "none", 300, false, true);
            Sources.sync(conn, List.of(def));
            Instant instNow = Instant.parse("2026-09-07T10:00:00Z");
            Sources.mark(conn, "s1", "abc123", "HEALTHY", instNow);

            Sources.sync(conn, List.of(def));
            assertEquals("abc123", Sources.lastRevision(conn, "s1"));
            assertEquals(instNow, Sources.lastSuccess(conn, "s1"));
        }
    }


    @Test
    void anUnchangedPollLeavesTheCursorWhereItWas() throws Exception {
        try (Connection conn = open()) {
            Db.schema(conn);
            Sources.sync(conn, List.of(new SourceDef("s1", "pub", "git", "https://example.invalid/r.git",
                    null, null, "OFFICIAL", "none", 300, false, true)));
            Sources.mark(conn, "s1", "abc123", "HEALTHY", Instant.parse("2026-09-07T10:00:00Z"));
            Sources.mark(conn, "s1", null, "DEGRADED", Instant.parse("2026-09-07T11:00:00Z"));
            assertEquals("abc123", Sources.lastRevision(conn, "s1"));
            assertEquals(Instant.parse("2026-09-07T10:00:00Z"), Sources.lastSuccess(conn, "s1"));
        }
    }


    @Test
    void aPollAttemptIsRecordedWithoutAnObservation() throws Exception {
        try (Connection conn = open()) {
            Db.schema(conn);
            Sources.sync(conn, List.of(new SourceDef("s1", "pub", "git", "https://example.invalid/r.git",
                    null, null, "OFFICIAL", "none", 300, false, true)));
            Observations.attempt(conn, "s1", "TRANSPORT_ERROR", Instant.now(), 12L, "unreachable", null);
            assertEquals(1L, count(conn, "poll_attempt"));
            assertFalse(Db.isEmpty(conn));
        }
    }


    private Connection open() throws Exception {
        return DriverManager.getConnection(
                "jdbc:h2:file:" + dirTmp.resolve("feed") + ";DB_CLOSE_ON_EXIT=FALSE", "feed", "");
    }


    private long count(Connection conn, String nameTable) throws Exception {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("select count(*) from " + nameTable)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }
}
