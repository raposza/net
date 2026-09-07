/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Rebuilds the index from the two things that are primary: the poll journal,
 * which holds every attempt and every observation it produced, and the evidence
 * store, which holds the bodies those observations point at.
 *
 * This runs by itself whenever the worker starts against an empty index, which
 * is what makes deleting the database file a safe operation rather than a loss.
 * Nothing here reaches the network and nothing here interprets a body: a rebuild
 * indexes evidence, it does not read it.
 *
 * Author Claude/bentzn
 */
public final class Rebuild {

    private Rebuild() {
    }


    /** What a rebuild replayed, and what it could not. */
    public record Result(int cntAttempt, int cntObservation, int cntMissing) {
    }


    /**
     * Replays the journal into an empty index.
     *
     * @param conn an open connection, schema already applied
     * @param dirEvidence the evidence store root
     * @param dirJournal the journal root
     * @param lstDef the source definitions, so a journal entry has a source to
     *        reference
     * @return what was replayed
     * @throws SQLException when a write fails
     * @throws IOException when the journal cannot be read
     */
    public static Result run(Connection conn, Path dirEvidence, Path dirJournal, List<SourceDef> lstDef)
            throws SQLException, IOException {
        Sources.sync(conn, lstDef);
        List<Journal.Entry> lstEntry = Journal.read(dirJournal);
        int cntObservation = 0;
        int cntMissing = 0;
        conn.setAutoCommit(false);
        try {
            for (Journal.Entry entry : lstEntry) {
                Instant instAttempt = instant(entry.attemptedAt());
                String idNewest = null;
                for (Banked banked : entry.lstBanked()) {
                    if (!Evidence.has(dirEvidence, banked.keyStorage())) {
                        System.err.println("rebuild: evidence absent for " + banked.keyStorage());
                        cntMissing++;
                        continue;
                    }
                    idNewest = Observations.insert(conn, entry.sourceId(), banked, instAttempt,
                            entry.collectorVersion());
                    cntObservation++;
                }
                Observations.attempt(conn, entry.sourceId(), entry.outcome(), instAttempt,
                        entry.durationMillis(), entry.detail(), idNewest);
                if (!"TRANSPORT_ERROR".equals(entry.outcome()) && !"HTTP_ERROR".equals(entry.outcome())) {
                    Sources.mark(conn, entry.sourceId(), entry.cursor(), "HEALTHY", instAttempt);
                }
            }
            conn.commit();
        }
        catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        }
        finally {
            conn.setAutoCommit(true);
        }
        return new Result(lstEntry.size(), cntObservation, cntMissing);
    }


    /**
     * Throws the index away and replays it. This is the same work the worker does
     * on its own against an empty index; the role exists for the case where the
     * index is present but wrong.
     */
    public static void force() {
        try (Connection conn = Db.connection()) {
            Db.drop(conn);
            Db.schema(conn);
            Result res = run(conn, Config.evidenceDir(), Config.journalDir(), Sources.load());
            System.out.println("rebuilt " + res.cntAttempt() + " attempts, " + res.cntObservation()
                    + " observations, " + res.cntMissing() + " bodies missing");
        }
        catch (SQLException | IOException e) {
            System.err.println("rebuild failed: " + e);
        }
    }


    private static Instant instant(String stampIso) {
        try {
            return Instant.parse(stampIso);
        }
        catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
