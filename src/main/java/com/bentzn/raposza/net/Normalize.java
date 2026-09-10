/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads banked bodies into claims in the index.
 *
 * Every observation of a source that has a normalizer is read once per
 * normalizer version, and the outcome is recorded whether it produced claims or
 * was refused, so a refused body is not read again on every turn. When the
 * version changes, the claims and outcomes of the old one are removed and every
 * body is read again. Claims are derived: they are rebuilt from the evidence
 * store like everything else in the index.
 *
 * Author Claude/bentzn
 */
public final class Normalize {

    private static final List<Normalizer> LST_NORMALIZER = List.of(new ScheduleNormalizer());

    private static final int LEN_DETAIL = 500;


    private Normalize() {
    }


    /** What one pass read. */
    public record Result(int cntObservation, int cntClaim, int cntRefused) {
    }


    /**
     * @return every normalizer, in the order they run
     */
    public static List<Normalizer> all() {
        return LST_NORMALIZER;
    }


    /**
     * Reads every body not yet read, then re-derives the events when that read
     * anything or the index holds no event yet. This is the one call the worker,
     * collect and rebuild make after banking.
     *
     * @param conn an open connection, schema applied
     * @param dirEvidence the evidence store root
     * @throws SQLException when the index cannot be read or written
     */
    public static void pass(Connection conn, Path dirEvidence) throws SQLException {
        Result res = pending(conn, dirEvidence);
        report(res);
        if (res.cntObservation() == 0 && !Events.isEmpty(conn)) {
            return;
        }
        int cntEvent = Events.refresh(conn);
        if (res.cntObservation() > 0 || cntEvent > 0) {
            System.out.println("events derived: " + cntEvent);
        }
    }


    /**
     * @param idSource a source id
     * @return the normalizer that reads it, or null when none does
     */
    public static Normalizer forSource(String idSource) {
        for (Normalizer norm : LST_NORMALIZER) {
            if (norm.sourceId().equals(idSource))
                return norm;
        }
        return null;
    }


    /**
     * Reads every observation no current normalizer has read yet.
     *
     * @param conn an open connection, schema applied
     * @param dirEvidence the evidence store root
     * @return what was read
     * @throws SQLException when the index cannot be read or written
     */
    public static Result pending(Connection conn, Path dirEvidence) throws SQLException {
        int cntObservation = 0;
        int cntClaim = 0;
        int cntRefused = 0;
        for (Normalizer norm : LST_NORMALIZER) {
            String authority = authority(conn, norm.sourceId());
            if (authority == null) {
                continue;
            }
            retire(conn, norm);
            for (String[] arrObs : unread(conn, norm)) {
                String idObs = arrObs[0];
                String outcome;
                String detail = null;
                List<Claim> lstClaim = List.of();
                try {
                    lstClaim = norm.normalize(Evidence.get(dirEvidence, arrObs[1]));
                    outcome = "NORMALIZED";
                }
                catch (Normalizer.Refused e) {
                    outcome = "REFUSED";
                    detail = e.getMessage();
                }
                catch (IOException e) {
                    outcome = "REFUSED";
                    detail = "evidence unreadable: " + e;
                }
                write(conn, norm, authority, idObs, lstClaim, outcome, detail);
                cntObservation++;
                cntClaim += lstClaim.size();
                if ("REFUSED".equals(outcome)) {
                    cntRefused++;
                    System.err.println("normalize: " + norm.id() + " refused " + idObs + ": " + detail);
                }
            }
        }
        return new Result(cntObservation, cntClaim, cntRefused);
    }


    /**
     * Prints one line when a pass read anything, and nothing when it did not.
     *
     * @param res what a pass read
     */
    public static void report(Result res) {
        if (res.cntObservation() > 0) {
            System.out.println("normalized " + res.cntObservation() + " observations into " + res.cntClaim()
                    + " claims, " + res.cntRefused() + " refused");
        }
    }


    /**
     * @param idObs the observation
     * @param idNormalizer the normalizer and version
     * @param subjectRef the subject reference
     * @param field the field
     * @return the claim identifier, derived so a second reading is the same row
     */
    public static String claimId(String idObs, String idNormalizer, String subjectRef, String field) {
        return "clm_" + hex(sha256(idObs + "\u0000" + idNormalizer + "\u0000" + subjectRef + "\u0000" + field))
                .substring(0, 32);
    }


    private static String authority(Connection conn, String idSource) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("select source_authority from source where id = ?")) {
            stmt.setString(1, idSource);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }


    private static void retire(Connection conn, Normalizer norm) throws SQLException {
        String sqlObs = "select id from source_observation where source_id = ?";
        for (String nameTable : List.of("claim", "normalization")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "delete from " + nameTable + " where normalizer <> ? and observation_id in (" + sqlObs + ")")) {
                stmt.setString(1, norm.id());
                stmt.setString(2, norm.sourceId());
                stmt.executeUpdate();
            }
        }
    }


    private static List<String[]> unread(Connection conn, Normalizer norm) throws SQLException {
        String sqlSel = "select o.id, o.storage_key from source_observation o where o.source_id = ?"
                + " and not exists (select 1 from normalization n where n.observation_id = o.id and n.normalizer = ?)"
                + " order by o.retrieved_at, o.id";
        List<String[]> lstOut = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sqlSel)) {
            stmt.setString(1, norm.sourceId());
            stmt.setString(2, norm.id());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    lstOut.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        return lstOut;
    }


    private static void write(Connection conn, Normalizer norm, String authority, String idObs, List<Claim> lstClaim,
            String outcome, String detail) throws SQLException {
        String sqlClaim = "merge into claim (id, observation_id, normalizer, subject_network, subject_kind,"
                + " subject_period, subject_ref, field, claim_value, value_precision, raw, source_authority,"
                + " confidence) key (id) values (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String sqlOutcome = "merge into normalization (observation_id, normalizer, outcome, claim_count, detail)"
                + " key (observation_id, normalizer) values (?,?,?,?,?)";
        boolean isAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement stmt = conn.prepareStatement(sqlClaim)) {
                for (Claim claim : lstClaim) {
                    stmt.setString(1, claimId(idObs, norm.id(), claim.subjectRef(), claim.field()));
                    stmt.setString(2, idObs);
                    stmt.setString(3, norm.id());
                    stmt.setString(4, claim.subjectNetwork());
                    stmt.setString(5, claim.subjectKind());
                    stmt.setString(6, claim.subjectPeriod());
                    stmt.setString(7, claim.subjectRef());
                    stmt.setString(8, claim.field());
                    stmt.setString(9, claim.value());
                    stmt.setString(10, claim.precision());
                    stmt.setString(11, claim.raw());
                    stmt.setString(12, authority);
                    stmt.setString(13, claim.confidence());
                    stmt.addBatch();
                }
                if (!lstClaim.isEmpty()) {
                    stmt.executeBatch();
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement(sqlOutcome)) {
                stmt.setString(1, idObs);
                stmt.setString(2, norm.id());
                stmt.setString(3, outcome);
                stmt.setInt(4, lstClaim.size());
                stmt.setString(5, detail == null || detail.length() <= LEN_DETAIL ? detail
                        : detail.substring(0, LEN_DETAIL));
                stmt.executeUpdate();
            }
            conn.commit();
        }
        catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        }
        finally {
            conn.setAutoCommit(isAuto);
        }
    }


    private static byte[] sha256(String textIn) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(textIn.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }


    private static String hex(byte[] bytesDigest) {
        StringBuilder sbHex = new StringBuilder(bytesDigest.length * 2);
        for (byte bDigest : bytesDigest) {
            sbHex.append(Character.forDigit((bDigest >> 4) & 0xF, 16));
            sbHex.append(Character.forDigit(bDigest & 0xF, 16));
        }
        return sbHex.toString();
    }
}
