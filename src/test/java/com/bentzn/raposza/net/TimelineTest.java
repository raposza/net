/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The planned window and the change log: what each includes, what each leaves
 * out, and in what order.
 *
 * Author Claude/bentzn
 */
class TimelineTest {

    private static final String SOURCE = ScheduleNormalizer.SOURCE_ID;

    private static final Instant INST_NOW = Instant.parse("2026-10-15T07:00:00Z");

    @TempDir
    Path dirTmp;


    @Test
    void plannedHoldsTheScheduleOfTheComingFourWeeksCancelledIncluded() {
        List<Object> lstEvent = List.of(
                event("e_before", "2026-10-14", "CONFIRMED", false, SOURCE),
                event("e_today", "2026-10-15", "TENTATIVE", false, SOURCE),
                event("e_cancel", "2026-10-20", "CANCELLED", false, SOURCE),
                event("e_last", "2026-11-12", "CONFIRMED", false, SOURCE),
                event("e_after", "2026-11-13", "CONFIRMED", false, SOURCE),
                event("e_gone", "2026-10-21", "CONFIRMED", true, SOURCE),
                event("e_other", "2026-10-22", "CONFIRMED", false, "sync-global-info-mainnet"));

        List<Object> lstPlanned = Timeline.planned(lstEvent, INST_NOW);

        List<String> lstId = new ArrayList<>();
        for (Object objOne : lstPlanned) {
            lstId.add(String.valueOf(((Map<?, ?>) objOne).get("eventId")));
        }
        assertEquals(List.of("e_today", "e_cancel", "e_last"), lstId,
                "today through 28 days ahead, by date; withdrawn and other sources left out");
        Map<?, ?> mapCancel = (Map<?, ?>) lstPlanned.get(1);
        assertEquals("CANCELLED", mapCancel.get("status"));
        assertEquals(SOURCE, mapCancel.get("sourceId"));
        assertEquals("obs_e_cancel", mapCancel.get("observationId"));
    }


    @Test
    void theChangeLogHoldsWhatMovedAndNotTheFirstLook() throws Exception {
        List<Path> lstBody = FixtureTest.bodies(SOURCE);
        byte[] bytesFirst = Files.readAllBytes(lstBody.get(0));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode nodeRoot = mapper.readTree(bytesFirst);
        String ref = null;
        for (JsonNode nodeRec : nodeRoot) {
            if ("To Be Confirmed".equals(nodeRec.get("status").asText()) && !nodeRec.get("type").asText().isEmpty()) {
                ref = nodeRec.get("id").asText();
                ((ObjectNode) nodeRec).put("status", "Confirmed");
                break;
            }
        }
        assertTrue(ref != null, "the fixture carries a tentative record");
        byte[] bytesSecond = mapper.writeValueAsBytes(nodeRoot);

        Path dirEvidence = dirTmp.resolve("evidence");
        SourceDef def = new SourceDef(SOURCE, "test", "http", "http://127.0.0.1/schedule.json", null, null,
                "OFFICIAL", "none", 300, false, true);
        Instant instFirst = Instant.parse("2026-10-01T08:00:00Z");
        Instant instSecond = Instant.parse("2026-10-02T08:00:00Z");
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:file:" + dirTmp.resolve("index") + ";DB_CLOSE_ON_EXIT=FALSE", "feed", "")) {
            Db.schema(conn);
            Sources.sync(conn, List.of(def));
            bank(conn, dirEvidence, bytesFirst, instFirst);
            bank(conn, dirEvidence, bytesSecond, instSecond);
            Normalize.pass(conn, dirEvidence);

            List<Object> lstChange = Timeline.changes(conn, INST_NOW);
            assertEquals(1, lstChange.size(), "the first body created every event, and only one record moved");
            Map<?, ?> mapOne = (Map<?, ?>) lstChange.get(0);
            assertEquals(Events.eventId(SOURCE, ref), mapOne.get("eventId"));
            assertEquals("2026-10-02T08:00:00Z", mapOne.get("observedAt"));
            assertEquals(Integer.valueOf(2), mapOne.get("revision"));
            assertEquals(SOURCE, mapOne.get("sourceId"));
            assertEquals(List.of("EVENT_CONFIRMED"), mapOne.get("types"));
            List<?> lstField = (List<?>) mapOne.get("changes");
            assertEquals(1, lstField.size());
            Map<?, ?> mapField = (Map<?, ?>) lstField.get(0);
            assertEquals("status", mapField.get("field"));
            assertEquals("TENTATIVE", mapField.get("old"));
            assertEquals("CONFIRMED", mapField.get("new"));
            assertEquals(Dataset.map("from", "2026-10-01T08:00:00Z", "to", "2026-10-02T08:00:00Z"),
                    mapOne.get("afterGap"), "nothing polled the source for a day before it showed the change");

            Observations.attempt(conn, SOURCE, "UNCHANGED", instSecond.minusSeconds(300), 10L, null, null);
            assertEquals(null, ((Map<?, ?>) Timeline.changes(conn, INST_NOW).get(0)).get("afterGap"),
                    "a source polled one interval earlier was watching when the change came");

            assertEquals(0, Timeline.changes(conn, instSecond.plusSeconds(86400L * (Timeline.DAYS_PAST + 1))).size(),
                    "a revision older than the window is not in the log");
        }
    }


    @Test
    void theSpansNobodyWatchedAreNamed() throws Exception {
        SourceDef def = new SourceDef(SOURCE, "test", "http", "http://127.0.0.1/schedule.json", null, null,
                "OFFICIAL", "none", 300, false, true);
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:file:" + dirTmp.resolve("watched") + ";DB_CLOSE_ON_EXIT=FALSE", "feed", "")) {
            Db.schema(conn);
            Sources.sync(conn, List.of(def));
            for (String stampAt : List.of("2026-09-30T12:00:00Z", "2026-10-01T07:05:00Z", "2026-10-01T07:10:00Z",
                    "2026-10-03T00:00:00Z", "2026-10-15T06:55:00Z")) {
                Observations.attempt(conn, SOURCE, "UNCHANGED", Instant.parse(stampAt), 10L, null, null);
            }
            List<Object> lstGap = Timeline.unwatched(conn, INST_NOW);
            assertEquals(List.of(
                    Dataset.map("from", "2026-10-01T07:10:00Z", "to", "2026-10-03T00:00:00Z"),
                    Dataset.map("from", "2026-10-03T00:00:00Z", "to", "2026-10-15T06:55:00Z")), lstGap,
                    "a poll before the window does not count, and five minutes is not a gap");
        }
    }


    private static void bank(Connection conn, Path dirEvidence, byte[] bytesBody, Instant instAt) throws Exception {
        String keyStorage = Evidence.put(dirEvidence, bytesBody);
        String shaBody = keyStorage.substring("sha256/".length());
        String idObs = Observations.insert(conn, SOURCE, new Banked(keyStorage, shaBody, "application/json", shaBody,
                null, Integer.valueOf(200), null, null), instAt, "test");
        Observations.attempt(conn, SOURCE, "CHANGED", instAt, 10L, null, idObs);
    }


    private static Map<String, Object> event(String id, String dayFrom, String status, boolean isWithdrawn,
            String idSource) {
        return Dataset.map(
                "id", id,
                "kind", "NETWORK_UPGRADE",
                "network", "MAINNET",
                "status", status,
                "withdrawn", Boolean.valueOf(isWithdrawn),
                "effective", Dataset.map("from", dayFrom, "to", null, "precision", "DATE"),
                "version", Dataset.map("value", "0.9.2", "precision", "PATCH", "change", "UPGRADE"),
                "title", "MainNet upgrades to Splice 0.9.2",
                "provenance", List.of(Dataset.map("sourceId", idSource, "observationId", "obs_" + id,
                        "normalizer", "test")));
    }
}
