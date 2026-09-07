/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Observations and poll attempts.
 *
 * An observation identifier is DERIVED from the source and the revision rather
 * than allocated, so a replay of the same state is the same row and cannot bank
 * a second copy of something that was only ever retrieved once. For a transport
 * with no revision of its own, the content hash is the revision, which gives the
 * same property: identical bytes are one observation.
 *
 * Author Claude/bentzn
 */
public final class Observations {

    private Observations() {
    }


    /**
     * @param idSource the source
     * @param revision the revision observed
     * @return the identifier that state has, on every run, forever
     */
    public static String id(String idSource, String revision) {
        return "obs_" + hex(sha256(idSource + "\u0000" + revision)).substring(0, 32);
    }


    /**
     * Inserts one observation. Replaying the same state of the same source
     * rewrites the same row with the same values, because the identifier is
     * derived from exactly that pair.
     *
     * @param conn an open connection
     * @param idSource the source
     * @param banked what the collector banked
     * @param instRetrieved when the poll ran
     * @param verCollector which collector produced it
     * @return the observation identifier
     * @throws SQLException when the write fails
     */
    public static String insert(Connection conn, String idSource, Banked banked, Instant instRetrieved,
            String verCollector) throws SQLException {
        String idObs = id(idSource, banked.revision());
        String sqlIns = "merge into source_observation (id, source_id, mode, retrieved_at, content_sha256,"
                + " media_type, storage_key, collector_version, source_revision, last_modified, etag,"
                + " http_status) key (id) values (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sqlIns)) {
            stmt.setString(1, idObs);
            stmt.setString(2, idSource);
            stmt.setString(3, "LIVE");
            stmt.setObject(4, OffsetDateTime.ofInstant(instRetrieved, ZoneOffset.UTC));
            stmt.setString(5, banked.shaContent());
            text(stmt, 6, banked.mediaType());
            stmt.setString(7, banked.keyStorage());
            stmt.setString(8, verCollector);
            stmt.setString(9, banked.revision());
            text(stmt, 10, banked.upstreamAt() != null ? banked.upstreamAt() : banked.lastModified());
            text(stmt, 11, banked.etag());
            if (banked.httpStatus() == null) {
                stmt.setNull(12, Types.INTEGER);
            }
            else {
                stmt.setInt(12, banked.httpStatus().intValue());
            }
            stmt.executeUpdate();
        }
        return idObs;
    }


    /**
     * Records one retrieval attempt, whether or not it produced anything. This is
     * what source health is computed from; it is operational, not evidence.
     *
     * @param conn an open connection
     * @param idSource the source
     * @param nameOutcome CHANGED, UNCHANGED, HTTP_ERROR, TRANSPORT_ERROR or PARSE_ERROR
     * @param instAttempt when it ran
     * @param msDuration how long it took
     * @param textDetail one line for an operator, or null
     * @param idObs the newest observation it produced, or null
     * @throws SQLException when the write fails
     */
    public static void attempt(Connection conn, String idSource, String nameOutcome, Instant instAttempt,
            long msDuration, String textDetail, String idObs) throws SQLException {
        String sqlIns = "insert into poll_attempt (source_id, attempted_at, outcome, duration_millis,"
                + " detail, observation_id) values (?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sqlIns)) {
            stmt.setString(1, idSource);
            stmt.setObject(2, OffsetDateTime.ofInstant(instAttempt, ZoneOffset.UTC));
            stmt.setString(3, nameOutcome);
            stmt.setInt(4, (int) Math.min(msDuration, Integer.MAX_VALUE));
            text(stmt, 5, textDetail == null || textDetail.length() <= 500 ? textDetail
                    : textDetail.substring(0, 500));
            text(stmt, 6, idObs);
            stmt.executeUpdate();
        }
    }


    private static void text(PreparedStatement stmt, int posArg, String valText) throws SQLException {
        if (valText == null) {
            stmt.setNull(posArg, Types.VARCHAR);
        }
        else {
            stmt.setString(posArg, valText);
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
