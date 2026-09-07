/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The claim that the database is derived, tested rather than asserted: collect
 * into evidence and journal, index it, throw the index away, rebuild, and
 * compare row for row.
 *
 * Author Claude/bentzn
 */
class RebuildTest {

    @TempDir
    Path dirTmp;


    @Test
    void evidenceAndJournalReconstructTheIndex() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirUp = repo();
        SourceDef def = new SourceDef("test-source", "test", "git", dirUp.toString(), null, null,
                "OFFICIAL", "none", 300, false, true);
        Path dirEvidence = dirTmp.resolve("evidence");
        Path dirJournal = dirTmp.resolve("journal");

        Poll resOne = GitCollector.collect(def, dirTmp.resolve("git"), dirEvidence, null);
        assertEquals("CHANGED", resOne.outcome(), resOne.detail());
        assertEquals(2, resOne.lstBanked().size());
        journal(dirJournal, def, resOne, Instant.parse("2026-09-07T10:00:00Z"));

        Poll resTwo =
                GitCollector.collect(def, dirTmp.resolve("git"), dirEvidence, resOne.cursor());
        assertEquals("UNCHANGED", resTwo.outcome(), resTwo.detail());
        journal(dirJournal, def, resTwo, Instant.parse("2026-09-07T10:05:00Z"));

        try (Connection conn = open("first")) {
            Db.schema(conn);
            Rebuild.Result res = Rebuild.run(conn, dirEvidence, dirJournal, List.of(def));
            assertEquals(2, res.cntAttempt());
            assertEquals(2, res.cntObservation());
            assertEquals(0, res.cntMissing());
            assertFalse(Db.isEmpty(conn));
        }

        try (Connection conn = open("second")) {
            Db.schema(conn);
            assertTrue(Db.isEmpty(conn), "a fresh file must look empty, which is what triggers a rebuild");
            Rebuild.run(conn, dirEvidence, dirJournal, List.of(def));

            assertEquals(2L, count(conn, "source_observation"));
            assertEquals(2L, count(conn, "poll_attempt"));
            assertEquals(resOne.cursor(), Sources.lastRevision(conn, "test-source"));
            assertEquals(Instant.parse("2026-09-07T10:05:00Z"), Sources.lastSuccess(conn, "test-source"));

            List<String> lstRevision = strings(conn,
                    "select source_revision from source_observation order by retrieved_at, id");
            for (Banked obs : resOne.lstBanked()) {
                assertTrue(lstRevision.contains(obs.revision()), obs.revision());
            }
            assertEquals(Instant.parse("2026-09-07T10:00:00Z"),
                    instants(conn, "select retrieved_at from source_observation").get(0));
        }
    }


    @Test
    void replayingTheSameJournalTwiceBanksNothingTwice() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirUp = repo();
        SourceDef def = new SourceDef("test-source", "test", "git", dirUp.toString(), null, null,
                "OFFICIAL", "none", 300, false, true);
        Path dirEvidence = dirTmp.resolve("evidence");
        Path dirJournal = dirTmp.resolve("journal");
        Poll res = GitCollector.collect(def, dirTmp.resolve("git"), dirEvidence, null);
        journal(dirJournal, def, res, Instant.parse("2026-09-07T10:00:00Z"));

        try (Connection conn = open("twice")) {
            Db.schema(conn);
            Rebuild.run(conn, dirEvidence, dirJournal, List.of(def));
            Rebuild.run(conn, dirEvidence, dirJournal, List.of(def));
            assertEquals(2L, count(conn, "source_observation"));
        }
    }


    @Test
    void anHttpObservationSurvivesTheRoundTrip() throws Exception {
        Path dirEvidence = dirTmp.resolve("evidence");
        Path dirJournal = dirTmp.resolve("journal");
        SourceDef def = new SourceDef("http-source", "test", "http", "https://example.invalid/d.json",
                null, null, "OFFICIAL", "none", 300, false, true);
        byte[] bytesBody = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String keyStorage = Evidence.put(dirEvidence, bytesBody);
        String shaContent = keyStorage.substring("sha256/".length());
        Banked banked = new Banked(keyStorage, shaContent, "application/json", shaContent, null,
                Integer.valueOf(200), "\"v1\"", "Mon, 07 Sep 2026 10:00:00 GMT");
        Journal.append(dirJournal, new Journal.Entry("http-source", "2026-09-07T10:00:00Z", "CHANGED",
                12L, "7 bytes", shaContent, HttpCollector.VER_COLLECTOR, List.of(banked)));

        try (Connection conn = open("http")) {
            Db.schema(conn);
            Rebuild.Result res = Rebuild.run(conn, dirEvidence, dirJournal, List.of(def));
            assertEquals(1, res.cntObservation());
            assertEquals(0, res.cntMissing());
            HttpCollector.State state = Sources.lastHttp(conn, "http-source");
            assertEquals("\"v1\"", state.etag());
            assertEquals("Mon, 07 Sep 2026 10:00:00 GMT", state.lastModified());
            assertEquals(shaContent, state.shaContent());
        }
    }


    @Test
    void aMissingBodyIsCountedRatherThanFatal() throws Exception {
        Path dirJournal = dirTmp.resolve("journal");
        SourceDef def = new SourceDef("test-source", "test", "git", "https://example.invalid/r.git",
                null, null, "OFFICIAL", "none", 300, false, true);
        Journal.append(dirJournal, new Journal.Entry("test-source", "2026-09-07T10:00:00Z", "CHANGED",
                5L, "one commit", "deadbeef", GitCollector.VER_COLLECTOR,
                List.of(new Banked("sha256/" + "0".repeat(64), "0".repeat(64), "application/json",
                        "deadbeef", "2026-09-07T09:00:00Z", null, null, null))));

        try (Connection conn = open("missing")) {
            Db.schema(conn);
            Rebuild.Result res = Rebuild.run(conn, dirTmp.resolve("evidence"), dirJournal, List.of(def));
            assertEquals(1, res.cntAttempt());
            assertEquals(0, res.cntObservation());
            assertEquals(1, res.cntMissing());
            assertEquals(1L, count(conn, "poll_attempt"));
        }
    }


    @Test
    void theJournalSurvivesATruncatedLastLine() throws Exception {
        Path dirJournal = dirTmp.resolve("journal");
        Journal.append(dirJournal, new Journal.Entry("s1", "2026-09-07T10:00:00Z", "UNCHANGED",
                4L, "head abc", "abc", GitCollector.VER_COLLECTOR, List.of()));
        Files.writeString(Journal.file(dirJournal), "{\"sourceId\":\"s1\",\"outcome\":\"CHAN",
                java.nio.file.StandardOpenOption.APPEND);
        List<Journal.Entry> lstEntry = Journal.read(dirJournal);
        assertEquals(1, lstEntry.size());
        assertEquals("UNCHANGED", lstEntry.get(0).outcome());
    }


    private void journal(Path dirJournal, SourceDef def, Poll res, Instant instAttempt)
            throws IOException {
        Journal.append(dirJournal, new Journal.Entry(def.id(), instAttempt.toString(), res.outcome(),
                res.msDuration(), res.detail(), res.cursor(), GitCollector.VER_COLLECTOR,
                res.lstBanked()));
    }


    private Path repo() throws Exception {
        Path dirUp = dirTmp.resolve("up");
        Files.createDirectories(dirUp);
        git(dirUp, "init", "-q", "-b", "main", ".");
        git(dirUp, "config", "user.email", "test@example.invalid");
        git(dirUp, "config", "user.name", "test");
        Files.createDirectories(dirUp.resolve("configs"));
        Files.writeString(dirUp.resolve("configs/a.yaml"), "value: 1\n");
        git(dirUp, "add", "-A");
        git(dirUp, "commit", "-q", "-m", "one");
        Files.writeString(dirUp.resolve("configs/a.yaml"), "value: 2\n");
        git(dirUp, "add", "-A");
        git(dirUp, "commit", "-q", "-m", "two");
        return dirUp;
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


    private List<String> strings(Connection conn, String sqlQuery) throws Exception {
        List<String> lstOut = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlQuery)) {
            while (rs.next()) {
                lstOut.add(rs.getString(1));
            }
        }
        return lstOut;
    }


    private List<Instant> instants(Connection conn, String sqlQuery) throws Exception {
        List<Instant> lstOut = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlQuery)) {
            while (rs.next()) {
                lstOut.add(rs.getObject(1, java.time.OffsetDateTime.class).toInstant());
            }
        }
        return lstOut;
    }


    private void git(Path dirUp, String... argsGit) throws Exception {
        String[] argsFull = new String[argsGit.length + 1];
        argsFull[0] = "git";
        System.arraycopy(argsGit, 0, argsFull, 1, argsGit.length);
        ProcessBuilder bldProc = new ProcessBuilder(argsFull);
        bldProc.directory(dirUp.toFile());
        bldProc.redirectErrorStream(true);
        bldProc.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        assertEquals(0, bldProc.start().waitFor(), String.join(" ", argsFull));
    }


    private boolean hasGit() {
        try {
            ProcessBuilder bldProc = new ProcessBuilder("git", "--version");
            bldProc.redirectErrorStream(true);
            bldProc.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return bldProc.start().waitFor() == 0;
        }
        catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
