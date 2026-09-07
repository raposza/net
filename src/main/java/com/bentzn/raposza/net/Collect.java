/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The collection round: which sources are due, poll each, bank what came back.
 *
 * The journal line is written and forced to disk BEFORE the index is touched.
 * A poll that reached the index but not the journal would disappear the first
 * time the database file is deleted; the other order costs at worst a replayed
 * line the index already holds, which is idempotent. A poll whose journal write
 * fails is discarded rather than half-banked.
 *
 * The observations of one poll are then written in one transaction, so a run
 * that dies half way leaves no partial history and simply repeats.
 *
 * Author Claude/bentzn
 */
public final class Collect {

    private Collect() {
    }


    /**
     * Polls every enabled source, ignoring the schedule. This is the role an
     * operator runs by hand.
     *
     * @return the number of sources that failed
     */
    public static int once() {
        return round(false);
    }


    /**
     * Polls the enabled sources whose poll interval has elapsed.
     *
     * @return the number of sources that failed
     */
    public static int due() {
        return round(true);
    }


    private static int round(boolean respectSchedule) {
        List<SourceDef> lstDef;
        try {
            lstDef = Sources.load();
        }
        catch (IOException e) {
            System.err.println("collect: " + e);
            return 1;
        }
        int cntFail = 0;
        try (Connection conn = Db.connection()) {
            Db.schema(conn);
            Sources.sync(conn, lstDef);
            for (SourceDef def : due(conn, lstDef, respectSchedule)) {
                if (!poll(conn, def)) {
                    cntFail++;
                }
            }
        }
        catch (SQLException | IOException e) {
            System.err.println("collect: " + e);
            return 1;
        }
        return cntFail;
    }


    private static List<SourceDef> due(Connection conn, List<SourceDef> lstDef, boolean respectSchedule)
            throws SQLException {
        List<SourceDef> lstDue = new ArrayList<>();
        Instant instNow = Instant.now();
        for (SourceDef def : lstDef) {
            if (!def.enabled() || !def.isCollectable()) {
                continue;
            }
            if (!respectSchedule) {
                lstDue.add(def);
                continue;
            }
            Instant instLast = Sources.lastAttempt(conn, def.id());
            if (instLast == null || Duration.between(instLast, instNow).getSeconds() >= def.pollSeconds()) {
                lstDue.add(def);
            }
        }
        return lstDue;
    }


    private static boolean poll(Connection conn, SourceDef def) {
        Instant instAttempt = Instant.now();
        Poll res;
        String verCollector;
        try {
            if (def.isGit()) {
                verCollector = GitCollector.VER_COLLECTOR;
                res = GitCollector.collect(def, Config.gitDir(), Config.evidenceDir(),
                        Sources.lastRevision(conn, def.id()));
            }
            else {
                verCollector = HttpCollector.VER_COLLECTOR;
                res = HttpCollector.collect(def, Config.evidenceDir(), Sources.lastHttp(conn, def.id()));
            }
        }
        catch (SQLException e) {
            System.err.println("collect " + def.id() + ": " + e);
            return false;
        }
        if (!journal(def, res, instAttempt, verCollector)) {
            return false;
        }
        try {
            return store(conn, def, res, instAttempt, verCollector);
        }
        catch (SQLException e) {
            rollback(conn);
            System.err.println("collect " + def.id() + ": store failed: " + e);
            attempt(conn, def, "TRANSPORT_ERROR", instAttempt, res.msDuration(), "store failed: " + e, null);
            return false;
        }
    }


    /** @return false when the journal could not be written, which discards the poll */
    private static boolean journal(SourceDef def, Poll res, Instant instAttempt, String verCollector) {
        try {
            Journal.append(Config.journalDir(), new Journal.Entry(def.id(), instAttempt.toString(),
                    res.outcome(), res.msDuration(), res.detail(), res.cursor(), verCollector,
                    res.lstBanked()));
            return true;
        }
        catch (IOException e) {
            System.err.println("collect " + def.id() + ": journal not written, poll discarded: " + e);
            return false;
        }
    }


    private static boolean store(Connection conn, SourceDef def, Poll res, Instant instAttempt,
            String verCollector) throws SQLException {
        boolean isError = "TRANSPORT_ERROR".equals(res.outcome()) || "HTTP_ERROR".equals(res.outcome());
        String idNewest = null;
        if (!res.lstBanked().isEmpty()) {
            conn.setAutoCommit(false);
            try {
                for (Banked banked : res.lstBanked()) {
                    idNewest = Observations.insert(conn, def.id(), banked, instAttempt, verCollector);
                }
                Sources.mark(conn, def.id(), res.cursor(), "HEALTHY", instAttempt);
                conn.commit();
            }
            finally {
                conn.setAutoCommit(true);
            }
        }
        else {
            Sources.mark(conn, def.id(), null, isError ? "DEGRADED" : "HEALTHY", instAttempt);
        }
        attempt(conn, def, res.outcome(), instAttempt, res.msDuration(), res.detail(), idNewest);
        System.out.println("collect " + def.id() + ": " + res.outcome() + " " + res.detail()
                + " (" + res.lstBanked().size() + " observations, " + res.msDuration() + " ms)");
        return !isError;
    }


    private static void attempt(Connection conn, SourceDef def, String nameOutcome, Instant instAttempt,
            long msDuration, String textDetail, String idObs) {
        try {
            Observations.attempt(conn, def.id(), nameOutcome, instAttempt, msDuration, textDetail, idObs);
        }
        catch (SQLException e) {
            System.err.println("collect " + def.id() + ": poll attempt not recorded: " + e);
        }
    }


    private static void rollback(Connection conn) {
        try {
            if (!conn.getAutoCommit()) {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
        catch (SQLException e) {
            System.err.println("collect: rollback failed: " + e);
        }
    }
}
