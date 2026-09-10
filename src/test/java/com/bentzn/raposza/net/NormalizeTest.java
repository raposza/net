/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
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
 * Claims in the index, against real banked bodies: each body is read once per
 * normalizer version, a second pass reads nothing, and an index built with
 * another schema is recognised as stale.
 *
 * Author Claude/bentzn
 */
class NormalizeTest {

    @TempDir
    Path dirTmp;


    @Test
    void bankedScheduleBodiesAreReadIntoClaimsOnce() throws Exception {
        List<Path> lstBody = FixtureTest.bodies(ScheduleNormalizer.SOURCE_ID);
        Path dirEvidence = dirTmp.resolve("evidence");
        SourceDef def = new SourceDef(ScheduleNormalizer.SOURCE_ID, "test", "http", "http://127.0.0.1/schedule.json",
                null, null, "OFFICIAL", "none", 300, false, true);
        int cntEach = new ScheduleNormalizer().normalize(Files.readAllBytes(lstBody.get(0))).size();
        try (Connection conn = open("index")) {
            Db.schema(conn);
            Sources.sync(conn, List.of(def));
            Instant instAt = Instant.parse("2026-09-07T08:00:00Z");
            for (Path fileBody : lstBody) {
                String keyStorage = Evidence.put(dirEvidence, Files.readAllBytes(fileBody));
                String shaBody = keyStorage.substring("sha256/".length());
                Observations.insert(conn, def.id(), new Banked(keyStorage, shaBody, "application/json", shaBody, null,
                        Integer.valueOf(200), null, null), instAt, "test");
                instAt = instAt.plusSeconds(60);
            }

            Normalize.Result res = Normalize.pending(conn, dirEvidence);
            assertEquals(lstBody.size(), res.cntObservation());
            assertEquals(0, res.cntRefused());
            assertEquals(cntEach * lstBody.size(), res.cntClaim());
            assertEquals((long) cntEach * lstBody.size(), count(conn, "claim"));
            assertEquals((long) lstBody.size(), count(conn, "normalization"));

            Normalize.Result resAgain = Normalize.pending(conn, dirEvidence);
            assertEquals(0, resAgain.cntObservation(), "a body is read once per normalizer version");
            assertEquals((long) cntEach * lstBody.size(), count(conn, "claim"));
        }
    }


    @Test
    void anIndexBuiltWithAnotherSchemaIsStale() throws Exception {
        try (Connection conn = open("stale")) {
            assertFalse(Db.isStale(conn), "an empty file is not stale");
            assertFalse(Db.schema(conn));
            assertFalse(Db.isStale(conn));
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("update index_meta set meta_value = 'other' where meta_key = 'schema_sha256'");
            }
            assertTrue(Db.isStale(conn));
            assertTrue(Db.schema(conn), "a stale index is dropped rather than marked current");
            assertFalse(Db.isStale(conn));
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("drop table index_meta");
            }
            assertTrue(Db.isStale(conn), "an index from before the digest was recorded is stale");
        }
    }


    private Connection open(String nameDb) throws Exception {
        return DriverManager.getConnection(
                "jdbc:h2:file:" + dirTmp.resolve(nameDb) + ";DB_CLOSE_ON_EXIT=FALSE", "feed", "");
    }


    private long count(Connection conn, String nameTable) throws Exception {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("select count(*) from " + nameTable)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }
}
