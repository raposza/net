/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The two views of time a reader asks for after the current state: what is
 * planned in the weeks ahead, and everything that changed in the weeks behind.
 *
 * PLANNED is read from the published events of the SV Operations Schedule and
 * from nothing else: every record dated from the publication's UTC date up to
 * DAYS_PLANNED days ahead, both ends included. Cancelled records are kept and
 * carry their status, so a reader sees that something was called off rather than
 * that it silently left the list. Withdrawn records state nothing and are left
 * out. The date is the schedule's own date; upstream gives a clock time on some
 * records and no zone on any, so no time is published.
 *
 * CHANGES is the revision log of the index: every revision of every event whose
 * causing body was banked in the last DAYS_PAST days, newest first, with the
 * change types, the fields that moved, and the observation that moved them. Two
 * kinds of revision are left out. The revisions a source made at its FIRST
 * banked observation record that the feed began to look, not that anything
 * happened upstream. A revision in which only version_status moved records that
 * upstream could not read a version, or could again; that is an observation
 * failure and its recovery, not a change, and the event keeps its version
 * through it. The time is when the feed banked the body that showed the change,
 * which is not when the change was made upstream.
 *
 * A change whose source had not been polled for a while before the poll that
 * banked it carries afterGap, the span between that source's previous poll and
 * this one: the change was first SEEN at the end of it and HAPPENED somewhere in
 * it. A while is longer than twice the source's own interval, and never less
 * than GAP_SECONDS, so a source polled hourly is not flagged for being hourly.
 *
 * UNWATCHED is the other half of the log: the spans inside the window in which
 * no source was polled at all, so a quiet stretch of the log can be told from
 * one nobody was watching. A gap is any stretch longer than GAP_SECONDS
 * between two polls, or between the window's edges and the polls nearest them.
 *
 * Author Claude/bentzn
 */
public final class Timeline {

    /** How far ahead the planned list reaches, in days. */
    public static final int DAYS_PLANNED = 28;

    /** How far back the change log reaches, in days. */
    public static final int DAYS_PAST = 14;

    /** The longest stretch without a poll that still counts as watched. */
    public static final long GAP_SECONDS = 900L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Null sorts last; several of the fields compared are absent on some records. */
    private static final Comparator<String> CMP_TEXT = Comparator.nullsLast(Comparator.naturalOrder());


    private Timeline() {
    }


    /**
     * @param lstEvent the published events
     * @param instNow the moment of publication, whose UTC date opens the window
     * @return one record per schedule event dated inside the window, by date,
     *         then network, then kind, then title
     */
    public static List<Object> planned(List<Object> lstEvent, Instant instNow) {
        LocalDate dayToday = LocalDate.ofInstant(instNow, ZoneOffset.UTC);
        String dayFirst = dayToday.toString();
        String dayLast = dayToday.plusDays(DAYS_PLANNED).toString();
        List<Map<String, Object>> lstFound = new ArrayList<>();
        for (Object objEvent : lstEvent) {
            if (!(objEvent instanceof Map))
                continue;
            Map<String, Object> mapEvent = cast(objEvent);
            if ("true".equals(String.valueOf(mapEvent.get("withdrawn"))))
                continue;
            Map<String, Object> mapProv = provenance(mapEvent, ScheduleNormalizer.SOURCE_ID);
            if (mapProv == null)
                continue;
            String dayFrom = text(sub(mapEvent, "effective"), "from");
            if (dayFrom == null || dayFrom.compareTo(dayFirst) < 0 || dayFrom.compareTo(dayLast) > 0)
                continue;
            lstFound.add(Dataset.map(
                    "eventId", mapEvent.get("id"),
                    "network", mapEvent.get("network"),
                    "kind", mapEvent.get("kind"),
                    "status", mapEvent.get("status"),
                    "from", dayFrom,
                    "to", text(sub(mapEvent, "effective"), "to"),
                    "version", text(sub(mapEvent, "version"), "value"),
                    "versionPrecision", text(sub(mapEvent, "version"), "precision"),
                    "title", mapEvent.get("title"),
                    "description", mapEvent.get("description"),
                    "sourceId", mapProv.get("sourceId"),
                    "observationId", mapProv.get("observationId"),
                    "lastBankedAt", mapEvent.get("lastBankedAt")));
        }
        lstFound.sort(Comparator
                .comparing((Map<String, Object> mapOne) -> text(mapOne, "from"), CMP_TEXT)
                .thenComparing(mapOne -> text(mapOne, "network"), CMP_TEXT)
                .thenComparing(mapOne -> text(mapOne, "kind"), CMP_TEXT)
                .thenComparing(mapOne -> text(mapOne, "title"), CMP_TEXT)
                .thenComparing(mapOne -> text(mapOne, "eventId"), CMP_TEXT));
        return new ArrayList<>(lstFound);
    }


    /**
     * @param conn an open connection, schema applied
     * @param instNow the moment of publication; the log reaches DAYS_PAST days
     *        back from it
     * @return one record per revision, newest first
     * @throws SQLException when the index cannot be read
     */
    public static List<Object> changes(Connection conn, Instant instNow) throws SQLException {
        OffsetDateTime odtFrom = OffsetDateTime.ofInstant(instNow.minus(DAYS_PAST, ChronoUnit.DAYS), ZoneOffset.UTC);
        Map<String, Instant> mapFirst = firstPerSource(conn);
        Map<String, List<String>> mapType = types(conn, odtFrom);
        Map<String, Long> mapLimit = gapLimits(conn);
        Map<String, Object> mapGap = new HashMap<>();
        String sqlPrev = "select max(attempted_at) from poll_attempt where source_id = ? and attempted_at < ?";
        String sqlRead = "select r.event_id, r.revision, r.observation_id, r.observed_at, r.changes,"
                + " e.source_id, e.subject_ref, e.kind, e.network, e.title"
                + " from event_revision r join event e on e.id = r.event_id"
                + " where r.observed_at >= ?"
                + " order by r.observed_at desc, e.source_id, r.event_id, r.revision desc";
        List<Object> lstOut = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sqlRead);
                PreparedStatement stmtPrev = conn.prepareStatement(sqlPrev)) {
            stmt.setObject(1, odtFrom);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String idEvent = rs.getString(1);
                    int nRevision = rs.getInt(2);
                    Instant instObserved = rs.getObject(4, OffsetDateTime.class).toInstant();
                    String idSource = rs.getString(6);
                    if (nRevision == 1 && instObserved.equals(mapFirst.get(idSource)))
                        continue;
                    List<Object> lstField = fieldChanges(rs.getString(5));
                    if (isStatusOnly(lstField))
                        continue;
                    String keyGap = idSource + '\u0000' + instObserved;
                    if (!mapGap.containsKey(keyGap)) {
                        mapGap.put(keyGap, afterGap(stmtPrev, idSource, instObserved,
                                mapLimit.getOrDefault(idSource, Long.valueOf(GAP_SECONDS)).longValue()));
                    }
                    lstOut.add(Dataset.map(
                            "observedAt", Dataset.iso(instObserved),
                            "eventId", idEvent,
                            "revision", Integer.valueOf(nRevision),
                            "network", rs.getString(9),
                            "kind", rs.getString(8),
                            "subject", rs.getString(7),
                            "title", rs.getString(10),
                            "types", mapType.getOrDefault(idEvent + '\u0000' + nRevision, List.of()),
                            "changes", lstField,
                            "sourceId", idSource,
                            "observationId", rs.getString(3),
                            "afterGap", mapGap.get(keyGap)));
                }
            }
        }
        return lstOut;
    }


    /**
     * @param stmtPrev the query for a source's last poll before a moment
     * @param idSource the source
     * @param instObserved when the body that showed the change was banked
     * @param secLimit the longest silence that is not a gap for this source
     * @return from and to of the silence before this poll, or null when the
     *         source was polled within secLimit before it; from is null when the
     *         source had never been polled before
     * @throws SQLException when the read fails
     */
    private static Map<String, Object> afterGap(PreparedStatement stmtPrev, String idSource, Instant instObserved,
            long secLimit) throws SQLException {
        stmtPrev.setString(1, idSource);
        stmtPrev.setObject(2, OffsetDateTime.ofInstant(instObserved, ZoneOffset.UTC));
        Instant instPrev = null;
        try (ResultSet rs = stmtPrev.executeQuery()) {
            if (rs.next()) {
                OffsetDateTime odtPrev = rs.getObject(1, OffsetDateTime.class);
                instPrev = odtPrev == null ? null : odtPrev.toInstant();
            }
        }
        if (instPrev != null && instObserved.getEpochSecond() - instPrev.getEpochSecond() <= secLimit)
            return null;
        return Dataset.map("from", Dataset.iso(instPrev), "to", Dataset.iso(instObserved));
    }


    /**
     * @param conn an open connection
     * @return per source, the longest silence between two polls that is not a
     *         gap: twice its interval, never less than GAP_SECONDS
     * @throws SQLException when the read fails
     */
    private static Map<String, Long> gapLimits(Connection conn) throws SQLException {
        Map<String, Long> mapOut = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement("select id, poll_seconds from source");
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                mapOut.put(rs.getString(1), Long.valueOf(Math.max(GAP_SECONDS, 2L * rs.getInt(2))));
            }
        }
        return mapOut;
    }


    /**
     * @param instNow the moment of publication
     * @return the first moment the change log reaches back to
     */
    public static Instant changesFrom(Instant instNow) {
        return instNow.minus(DAYS_PAST, ChronoUnit.DAYS);
    }


    /**
     * @param conn an open connection, schema applied
     * @param instNow the moment of publication; the window is DAYS_PAST days back
     *        from it
     * @return every span inside the window longer than GAP_SECONDS in which no
     *         source was polled, oldest first, as from and to
     * @throws SQLException when the index cannot be read
     */
    public static List<Object> unwatched(Connection conn, Instant instNow) throws SQLException {
        Instant instFrom = changesFrom(instNow);
        List<Object> lstOut = new ArrayList<>();
        Instant instPrev = instFrom;
        String sqlRead = "select attempted_at from poll_attempt where attempted_at >= ? and attempted_at <= ?"
                + " order by attempted_at";
        try (PreparedStatement stmt = conn.prepareStatement(sqlRead)) {
            stmt.setObject(1, OffsetDateTime.ofInstant(instFrom, ZoneOffset.UTC));
            stmt.setObject(2, OffsetDateTime.ofInstant(instNow, ZoneOffset.UTC));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Instant instAt = rs.getObject(1, OffsetDateTime.class).toInstant();
                    gap(lstOut, instPrev, instAt);
                    instPrev = instAt;
                }
            }
        }
        gap(lstOut, instPrev, instNow);
        return lstOut;
    }


    private static void gap(List<Object> lstOut, Instant instFrom, Instant instTo) {
        if (instTo.getEpochSecond() - instFrom.getEpochSecond() > GAP_SECONDS) {
            lstOut.add(Dataset.map("from", Dataset.iso(instFrom), "to", Dataset.iso(instTo)));
        }
    }


    /**
     * @param lstField the fields one revision moved
     * @return true when the only field that moved is version_status: upstream
     *         failing to read a version, or reading it again
     */
    static boolean isStatusOnly(List<Object> lstField) {
        if (lstField.isEmpty())
            return false;
        for (Object objOne : lstField) {
            if (!(objOne instanceof Map) || !"version_status".equals(String.valueOf(((Map<?, ?>) objOne).get("field"))))
                return false;
        }
        return true;
    }


    /**
     * @param conn an open connection
     * @return per source, when it made its first revision, which is when its
     *         first banked body was read
     * @throws SQLException when the read fails
     */
    private static Map<String, Instant> firstPerSource(Connection conn) throws SQLException {
        String sqlRead = "select e.source_id, min(r.observed_at) from event_revision r"
                + " join event e on e.id = r.event_id group by e.source_id";
        Map<String, Instant> mapOut = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sqlRead); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                mapOut.put(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant());
            }
        }
        return mapOut;
    }


    /**
     * @param conn an open connection
     * @param odtFrom the start of the window
     * @return the change types of every revision in the window, keyed by event
     *         and revision, in the order they were recorded
     * @throws SQLException when the read fails
     */
    private static Map<String, List<String>> types(Connection conn, OffsetDateTime odtFrom) throws SQLException {
        String sqlRead = "select event_id, revision, change_type from event_change where observed_at >= ?"
                + " order by source_id, seq";
        Map<String, List<String>> mapOut = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sqlRead)) {
            stmt.setObject(1, odtFrom);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    mapOut.computeIfAbsent(rs.getString(1) + '\u0000' + rs.getInt(2), keyNew -> new ArrayList<>())
                            .add(rs.getString(3));
                }
            }
        }
        return mapOut;
    }


    /**
     * @param textJson the changes column, a JSON list of field, old and new
     * @return the list as published; a column that does not parse is published
     *         as no change rather than failing the publication
     */
    private static List<Object> fieldChanges(String textJson) {
        if (textJson == null || textJson.isBlank())
            return List.of();
        try {
            return MAPPER.readValue(textJson, new TypeReference<List<Object>>() {
            });
        }
        catch (JsonProcessingException e) {
            System.err.println("timeline: a change list did not parse: " + e.getMessage());
            return List.of();
        }
    }


    /**
     * @param mapEvent a published event
     * @param idSource the source wanted
     * @return its provenance record from that source, or null when that source
     *         did not describe it
     */
    private static Map<String, Object> provenance(Map<String, Object> mapEvent, String idSource) {
        Object objList = mapEvent.get("provenance");
        if (!(objList instanceof List))
            return null;
        for (Object objOne : (List<?>) objList) {
            if (objOne instanceof Map && idSource.equals(String.valueOf(((Map<?, ?>) objOne).get("sourceId"))))
                return cast(objOne);
        }
        return null;
    }


    private static Map<String, Object> sub(Map<String, Object> mapAny, String nameField) {
        Object objSub = mapAny.get(nameField);
        return objSub instanceof Map ? cast(objSub) : null;
    }


    private static String text(Map<String, Object> mapAny, String nameField) {
        if (mapAny == null)
            return null;
        Object objValue = mapAny.get(nameField);
        return objValue == null ? null : String.valueOf(objValue);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object objAny) {
        return (Map<String, Object>) objAny;
    }
}
