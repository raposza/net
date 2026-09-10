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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Events, revisions and change records, derived from the claims of a
 * normalizer over the observations of its source, in the order they were banked.
 *
 * One event per upstream record. The first observation that carries a record
 * creates its event at revision 1. Every later observation that changes one of
 * its fields makes a revision, with the fields that moved and a change record
 * naming what kind of move it was. An observation that carries the same fields
 * makes nothing, which is why a body re-sorted upstream makes no revision.
 *
 * A record that disappears from a later body is WITHDRAWN: the event keeps its
 * last status and is marked withdrawn, with the observation that no longer
 * carried it as the cause. Upstream marks a cancellation as Cancelled and keeps
 * the record, so a disappearance is not a cancellation, and saying so would
 * state something the source never said. A record that comes back is a
 * correction. Nothing here is derived from the date passing: STARTED and
 * COMPLETED are not published, because no observation says either happened.
 *
 * Only banked bodies are replayed. A poll that found the body unchanged saw the
 * same records again and changes no event; when a record was last SEEN is the
 * last poll that saw its last body, which the poll attempts in the index hold.
 * What is stored here is when that body was banked.
 *
 * The derivation is a pure function of the claims and their order, so the index
 * rebuilds it exactly. Every identifier is derived, and the change records carry
 * a sequence number per source that only ever grows as observations are added.
 *
 * Author Claude/bentzn
 */
public final class Events {

    /** The event fields a revision is made of, in the order they are compared. */
    public static final List<String> LST_FIELD = List.of("kind", "network", "status", "effective.from",
            "effective.to", "version", "version.precision", "version.change", "title", "description",
            "upstream.type", "withdrawn");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CREATED = "EVENT_CREATED";

    private static final String CONFIRMED = "EVENT_CONFIRMED";

    private static final String CANCELLED = "EVENT_CANCELLED";

    private static final String RESCHEDULED = "EVENT_RESCHEDULED";

    private static final String VERSION_ATTACHED = "EVENT_VERSION_ATTACHED";

    private static final String WITHDRAWN = "EVENT_WITHDRAWN";

    private static final String CORRECTED = "EVENT_CORRECTED";


    private Events() {
    }


    /**
     * One banked observation and the claims read from it.
     *
     * @param observationId the observation
     * @param bankedAt when the poll that banked it ran
     * @param lstClaim its claims
     */
    public record Snapshot(String observationId, Instant bankedAt, List<Claim> lstClaim) {
    }


    /**
     * One field that moved.
     *
     * @param field the event field
     * @param valueOld its value before, or null
     * @param valueNew its value after, or null
     */
    public record Change(String field, String valueOld, String valueNew) {
    }


    /**
     * An event as it stands after the last observation.
     *
     * @param id the event identifier
     * @param subjectRef the upstream reference of its record
     * @param mapField its fields, keyed as in LST_FIELD
     * @param revision its current revision
     * @param firstObservedAt when the first body that carried it was banked
     * @param lastBankedAt when the last body that carried it was banked
     * @param lastObservationId that last body
     */
    public record Event(String id, String subjectRef, Map<String, String> mapField, int revision,
            Instant firstObservedAt, Instant lastBankedAt, String lastObservationId) {
    }


    /**
     * One revision of one event.
     *
     * @param eventId the event
     * @param revision the revision number, from 1
     * @param observationId the observation that caused it
     * @param observedAt when that observation was banked
     * @param lstChange the fields that moved, in LST_FIELD order
     */
    public record Revision(String eventId, int revision, String observationId, Instant observedAt,
            List<Change> lstChange) {
    }


    /**
     * One change record.
     *
     * @param seq its position among the change records of its source
     * @param changeId its identifier
     * @param changeType what kind of move it records
     * @param eventId the event
     * @param revision the revision it belongs to
     * @param observedAt when the causing observation was banked
     */
    public record ChangeRecord(long seq, String changeId, String changeType, String eventId, int revision,
            Instant observedAt) {
    }


    /**
     * What a derivation produced.
     *
     * @param lstEvent the events, by subject reference
     * @param lstRevision every revision, in the order they happened
     * @param lstRecord every change record, in sequence order
     */
    public record Result(List<Event> lstEvent, List<Revision> lstRevision, List<ChangeRecord> lstRecord) {
    }


    /**
     * @param idSource the source the observations belong to
     * @param lstSnapshot its observations in the order they were banked
     * @return the events, revisions and change records they make
     */
    public static Result derive(String idSource, List<Snapshot> lstSnapshot) {
        Map<String, State> mapState = new TreeMap<>();
        List<Revision> lstRevision = new ArrayList<>();
        List<ChangeRecord> lstRecord = new ArrayList<>();
        long seqNext = 1L;
        for (Snapshot snap : lstSnapshot) {
            Map<String, Map<String, String>> mapNow = fields(snap.lstClaim());
            Set<String> setRef = new TreeSet<>(mapState.keySet());
            setRef.addAll(mapNow.keySet());
            for (String ref : setRef) {
                State stateOld = mapState.get(ref);
                Map<String, String> mapNew = mapNow.get(ref);
                List<String> lstType;
                if (stateOld == null) {
                    mapNew.put("withdrawn", "false");
                    State stateNew = new State(eventId(idSource, ref), ref, snap);
                    mapState.put(ref, stateNew);
                    lstType = List.of(CREATED);
                    seqNext = revise(stateNew, mapNew, snap, lstType, lstRevision, lstRecord, seqNext);
                    continue;
                }
                if (mapNew == null) {
                    if ("true".equals(stateOld.mapField.get("withdrawn"))) {
                        continue;
                    }
                    mapNew = new TreeMap<>(stateOld.mapField);
                    mapNew.put("withdrawn", "true");
                    seqNext = revise(stateOld, mapNew, snap, List.of(WITHDRAWN), lstRevision, lstRecord, seqNext);
                    continue;
                }
                mapNew.put("withdrawn", "false");
                stateOld.lastBankedAt = snap.bankedAt();
                stateOld.lastObservationId = snap.observationId();
                List<Change> lstChange = changes(stateOld.mapField, mapNew);
                if (lstChange.isEmpty()) {
                    continue;
                }
                seqNext = revise(stateOld, mapNew, snap, types(lstChange), lstRevision, lstRecord, seqNext);
            }
        }
        List<Event> lstEvent = new ArrayList<>();
        for (State state : mapState.values()) {
            lstEvent.add(new Event(state.id, state.ref, state.mapField, state.revision, state.firstObservedAt,
                    state.lastBankedAt, state.lastObservationId));
        }
        return new Result(lstEvent, lstRevision, lstRecord);
    }


    /**
     * Re-derives the events of every source that has a normalizer, from the
     * claims in the index, and replaces what the index held.
     *
     * @param conn an open connection, schema applied
     * @return the number of events the index now holds
     * @throws SQLException when the index cannot be read or written
     */
    public static int refresh(Connection conn) throws SQLException {
        int cntEvent = 0;
        for (Normalizer norm : Normalize.all()) {
            Result res = derive(norm.sourceId(), snapshots(conn, norm));
            write(conn, norm.sourceId(), res);
            cntEvent += res.lstEvent().size();
        }
        return cntEvent;
    }


    /**
     * @param conn an open connection
     * @return true when the index holds no event
     * @throws SQLException when the count fails
     */
    public static boolean isEmpty(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("select count(*) from event")) {
            return rs.next() && rs.getLong(1) == 0L;
        }
    }


    /**
     * @param idSource the source
     * @param ref the upstream reference of the record
     * @return the event identifier, the same on every rebuild
     */
    public static String eventId(String idSource, String ref) {
        return "evt_" + hex(sha256(idSource + "\u0000" + ref)).substring(0, 24);
    }


    private static long revise(State state, Map<String, String> mapNew, Snapshot snap, List<String> lstType,
            List<Revision> lstRevision, List<ChangeRecord> lstRecord, long seqNext) {
        List<Change> lstChange = changes(state.mapField, mapNew);
        state.mapField = mapNew;
        state.revision++;
        lstRevision.add(new Revision(state.id, state.revision, snap.observationId(), snap.bankedAt(), lstChange));
        long seqOut = seqNext;
        for (String nameType : lstType) {
            String idChange = "chg_" + hex(sha256(state.id + "\u0000" + state.revision + "\u0000" + nameType))
                    .substring(0, 24);
            lstRecord.add(new ChangeRecord(seqOut, idChange, nameType, state.id, state.revision, snap.bankedAt()));
            seqOut++;
        }
        return seqOut;
    }


    private static Map<String, Map<String, String>> fields(List<Claim> lstClaim) {
        Map<String, Map<String, String>> mapOut = new TreeMap<>();
        for (Claim claim : lstClaim) {
            Map<String, String> mapRef = mapOut.computeIfAbsent(claim.subjectRef(), refNew -> new TreeMap<>());
            mapRef.put("kind", claim.subjectKind());
            mapRef.put("network", claim.subjectNetwork());
            if (LST_FIELD.contains(claim.field())) {
                mapRef.put(claim.field(), claim.value());
            }
            if ("version".equals(claim.field())) {
                mapRef.put("version.precision", claim.precision());
            }
        }
        return mapOut;
    }


    private static List<Change> changes(Map<String, String> mapOld, Map<String, String> mapNew) {
        List<Change> lstOut = new ArrayList<>();
        for (String field : LST_FIELD) {
            String valueOld = mapOld.get(field);
            String valueNew = mapNew.get(field);
            if (!Objects.equals(valueOld, valueNew)) {
                lstOut.add(new Change(field, valueOld, valueNew));
            }
        }
        return lstOut;
    }


    /**
     * The kinds of move one revision records, in a fixed order. A field that
     * moved and is named by no specific kind makes the revision a correction too.
     */
    private static List<String> types(List<Change> lstChange) {
        List<String> lstOut = new ArrayList<>();
        Set<String> setCovered = new TreeSet<>();
        for (Change chg : lstChange) {
            if ("status".equals(chg.field()) && "CONFIRMED".equals(chg.valueNew())) {
                lstOut.add(CONFIRMED);
                setCovered.add(chg.field());
            }
        }
        for (Change chg : lstChange) {
            if ("status".equals(chg.field()) && "CANCELLED".equals(chg.valueNew())) {
                lstOut.add(CANCELLED);
                setCovered.add(chg.field());
            }
        }
        boolean isMoved = false;
        for (Change chg : lstChange) {
            if ("effective.from".equals(chg.field()) || "effective.to".equals(chg.field())) {
                isMoved = true;
                setCovered.add(chg.field());
            }
        }
        if (isMoved) {
            lstOut.add(RESCHEDULED);
        }
        for (Change chg : lstChange) {
            if ("version".equals(chg.field()) && chg.valueOld() == null && chg.valueNew() != null) {
                lstOut.add(VERSION_ATTACHED);
                setCovered.add("version");
                setCovered.add("version.precision");
            }
        }
        for (Change chg : lstChange) {
            if (!setCovered.contains(chg.field())) {
                lstOut.add(CORRECTED);
                break;
            }
        }
        return lstOut;
    }


    /**
     * Every poll of the source that banked a body this normalizer read, in the
     * order the polls ran. A body banked twice - upstream going back to an
     * earlier state - appears twice, which is why the order comes from the polls
     * and not from the observations. A body the normalizer refused is skipped: it
     * says nothing about which records exist.
     */
    private static List<Snapshot> snapshots(Connection conn, Normalizer norm) throws SQLException {
        String sqlRead = "select observation_id from normalization where normalizer = ? and outcome = 'NORMALIZED'";
        String sqlPoll = "select observation_id, attempted_at from poll_attempt where source_id = ?"
                + " and outcome = 'CHANGED' and observation_id is not null order by attempted_at, id";
        String sqlClaim = "select subject_ref, subject_network, subject_kind, subject_period, field, claim_value,"
                + " value_precision, raw, confidence from claim where observation_id = ? and normalizer = ?"
                + " order by subject_ref, field";
        Set<String> setRead = new TreeSet<>();
        try (PreparedStatement stmt = conn.prepareStatement(sqlRead)) {
            stmt.setString(1, norm.id());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    setRead.add(rs.getString(1));
                }
            }
        }
        List<Snapshot> lstOut = new ArrayList<>();
        Map<String, List<Claim>> mapClaim = new LinkedHashMap<>();
        try (PreparedStatement stmtPoll = conn.prepareStatement(sqlPoll);
                PreparedStatement stmtClaim = conn.prepareStatement(sqlClaim)) {
            stmtPoll.setString(1, norm.sourceId());
            try (ResultSet rsPoll = stmtPoll.executeQuery()) {
                while (rsPoll.next()) {
                    String idObs = rsPoll.getString(1);
                    if (!setRead.contains(idObs)) {
                        continue;
                    }
                    List<Claim> lstClaim = mapClaim.get(idObs);
                    if (lstClaim == null) {
                        lstClaim = claims(stmtClaim, idObs, norm.id());
                        mapClaim.put(idObs, lstClaim);
                    }
                    lstOut.add(new Snapshot(idObs, rsPoll.getObject(2, OffsetDateTime.class).toInstant(), lstClaim));
                }
            }
        }
        return lstOut;
    }


    private static List<Claim> claims(PreparedStatement stmtClaim, String idObs, String idNormalizer)
            throws SQLException {
        List<Claim> lstOut = new ArrayList<>();
        stmtClaim.setString(1, idObs);
        stmtClaim.setString(2, idNormalizer);
        try (ResultSet rs = stmtClaim.executeQuery()) {
            while (rs.next()) {
                lstOut.add(new Claim(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)));
            }
        }
        return lstOut;
    }


    private static void write(Connection conn, String idSource, Result res) throws SQLException {
        boolean isAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            for (String sqlDel : List.of("delete from event_change where source_id = ?",
                    "delete from event_revision where event_id in (select id from event where source_id = ?)",
                    "delete from event where source_id = ?")) {
                try (PreparedStatement stmt = conn.prepareStatement(sqlDel)) {
                    stmt.setString(1, idSource);
                    stmt.executeUpdate();
                }
            }
            insertEvents(conn, idSource, res.lstEvent());
            insertRevisions(conn, res.lstRevision());
            insertRecords(conn, idSource, res.lstRecord());
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


    private static void insertEvents(Connection conn, String idSource, List<Event> lstEvent) throws SQLException {
        String sqlIns = "insert into event (id, source_id, subject_ref, kind, network, status, effective_from,"
                + " effective_to, version, version_precision, version_change, title, description, upstream_type,"
                + " withdrawn, revision, first_observed_at, last_banked_at, last_observation_id)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sqlIns)) {
            for (Event evt : lstEvent) {
                Map<String, String> mapField = evt.mapField();
                stmt.setString(1, evt.id());
                stmt.setString(2, idSource);
                stmt.setString(3, evt.subjectRef());
                stmt.setString(4, mapField.get("kind"));
                stmt.setString(5, mapField.get("network"));
                stmt.setString(6, mapField.get("status"));
                stmt.setString(7, mapField.get("effective.from"));
                stmt.setString(8, mapField.get("effective.to"));
                stmt.setString(9, mapField.get("version"));
                stmt.setString(10, mapField.get("version.precision"));
                stmt.setString(11, mapField.get("version.change"));
                stmt.setString(12, mapField.get("title"));
                stmt.setString(13, mapField.get("description"));
                stmt.setString(14, mapField.get("upstream.type"));
                stmt.setBoolean(15, "true".equals(mapField.get("withdrawn")));
                stmt.setInt(16, evt.revision());
                stmt.setObject(17, OffsetDateTime.ofInstant(evt.firstObservedAt(), ZoneOffset.UTC));
                stmt.setObject(18, OffsetDateTime.ofInstant(evt.lastBankedAt(), ZoneOffset.UTC));
                stmt.setString(19, evt.lastObservationId());
                stmt.addBatch();
            }
            if (!lstEvent.isEmpty()) {
                stmt.executeBatch();
            }
        }
    }


    private static void insertRevisions(Connection conn, List<Revision> lstRevision) throws SQLException {
        String sqlIns = "insert into event_revision (event_id, revision, observation_id, observed_at, changes)"
                + " values (?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sqlIns)) {
            for (Revision rev : lstRevision) {
                stmt.setString(1, rev.eventId());
                stmt.setInt(2, rev.revision());
                stmt.setString(3, rev.observationId());
                stmt.setObject(4, OffsetDateTime.ofInstant(rev.observedAt(), ZoneOffset.UTC));
                stmt.setString(5, json(rev.lstChange()));
                stmt.addBatch();
            }
            if (!lstRevision.isEmpty()) {
                stmt.executeBatch();
            }
        }
    }


    private static void insertRecords(Connection conn, String idSource, List<ChangeRecord> lstRecord)
            throws SQLException {
        String sqlIns = "insert into event_change (change_id, source_id, seq, change_type, event_id, revision,"
                + " observed_at) values (?,?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sqlIns)) {
            for (ChangeRecord rec : lstRecord) {
                stmt.setString(1, rec.changeId());
                stmt.setString(2, idSource);
                stmt.setLong(3, rec.seq());
                stmt.setString(4, rec.changeType());
                stmt.setString(5, rec.eventId());
                stmt.setInt(6, rec.revision());
                stmt.setObject(7, OffsetDateTime.ofInstant(rec.observedAt(), ZoneOffset.UTC));
                stmt.addBatch();
            }
            if (!lstRecord.isEmpty()) {
                stmt.executeBatch();
            }
        }
    }


    private static String json(List<Change> lstChange) {
        List<Object> lstOut = new ArrayList<>();
        for (Change chg : lstChange) {
            Map<String, Object> mapOne = new LinkedHashMap<>();
            mapOne.put("field", chg.field());
            mapOne.put("old", chg.valueOld());
            mapOne.put("new", chg.valueNew());
            lstOut.add(mapOne);
        }
        try {
            return MAPPER.writeValueAsString(lstOut);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("a change list did not serialize", e);
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


    /** The running state of one event while observations are replayed. */
    private static final class State {

        private final String id;

        private final String ref;

        private final Instant firstObservedAt;

        private Map<String, String> mapField = new TreeMap<>();

        private int revision;

        private Instant lastBankedAt;

        private String lastObservationId;


        private State(String id, String ref, Snapshot snapFirst) {
            this.id = id;
            this.ref = ref;
            this.firstObservedAt = snapFirst.bankedAt();
            this.lastBankedAt = snapFirst.bankedAt();
            this.lastObservationId = snapFirst.observationId();
        }
    }
}
