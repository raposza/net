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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Events over real banked schedule bodies. The three bodies hold the same records
 * in different orders, so replayed in order they must create every event once
 * and revise none. The moves the bodies do not yet contain - a confirmation, a
 * new date, a record that disappears and comes back - are made by changing one
 * claim of the real claims, never by writing a body.
 *
 * Author Claude/bentzn
 */
class EventsTest {

    private static final String SOURCE = ScheduleNormalizer.SOURCE_ID;

    @TempDir
    Path dirTmp;


    @Test
    void bodiesInAnotherOrderCreateEveryEventOnceAndReviseNone() throws Exception {
        List<Events.Snapshot> lstSnap = new ArrayList<>();
        Instant instAt = Instant.parse("2026-09-07T08:47:34Z");
        for (Path fileBody : FixtureTest.bodies(SOURCE)) {
            lstSnap.add(new Events.Snapshot(fileBody.getFileName().toString(), instAt, claims(fileBody)));
            instAt = instAt.plusSeconds(86400);
        }
        Events.Result res = Events.derive(SOURCE, lstSnap);
        int cntRecord = refs(claims(FixtureTest.bodies(SOURCE).get(0))).size();
        assertEquals(cntRecord, res.lstEvent().size());
        assertEquals(cntRecord, res.lstRevision().size(), "a re-sorted body revises nothing");
        assertEquals(cntRecord, res.lstRecord().size());
        for (Events.ChangeRecord rec : res.lstRecord()) {
            assertEquals("EVENT_CREATED", rec.changeType());
        }
        for (Events.Event evt : res.lstEvent()) {
            assertEquals(1, evt.revision());
            assertEquals(Instant.parse("2026-09-07T08:47:34Z"), evt.firstObservedAt());
            assertEquals(lstSnap.get(lstSnap.size() - 1).observationId(), evt.lastObservationId());
        }
        assertEquals(res.lstRecord(), Events.derive(SOURCE, lstSnap).lstRecord(), "derivation is repeatable");
    }


    @Test
    void aConfirmationIsARevisionWithItsOwnChangeRecord() throws Exception {
        List<Claim> lstBase = claims(FixtureTest.bodies(SOURCE).get(0));
        String ref = firstRef(lstBase, "status", "TENTATIVE");
        List<Claim> lstNext = replace(lstBase, ref, "status", "CONFIRMED");
        Events.Result res = Events.derive(SOURCE, List.of(snap("a", 0, lstBase), snap("b", 1, lstNext)));
        Events.Event evt = event(res, ref);
        assertEquals(2, evt.revision());
        assertEquals("CONFIRMED", evt.mapField().get("status"));
        Events.ChangeRecord rec = res.lstRecord().get(res.lstRecord().size() - 1);
        assertEquals("EVENT_CONFIRMED", rec.changeType());
        assertEquals(evt.id(), rec.eventId());
        Events.Revision rev = res.lstRevision().get(res.lstRevision().size() - 1);
        assertEquals(List.of(new Events.Change("status", "TENTATIVE", "CONFIRMED")), rev.lstChange());
        assertEquals("b", rev.observationId());
    }


    @Test
    void aMovedDateIsRescheduled() throws Exception {
        List<Claim> lstBase = claims(FixtureTest.bodies(SOURCE).get(0));
        String ref = firstRef(lstBase, "status", "CONFIRMED");
        List<Claim> lstNext = replace(lstBase, ref, "effective.from", "2027-01-04");
        Events.Result res = Events.derive(SOURCE, List.of(snap("a", 0, lstBase), snap("b", 1, lstNext)));
        assertEquals("EVENT_RESCHEDULED", res.lstRecord().get(res.lstRecord().size() - 1).changeType());
        assertEquals("2027-01-04", event(res, ref).mapField().get("effective.from"));
    }


    @Test
    void aRecordThatDisappearsIsWithdrawnNotCancelledAndItsReturnIsACorrection() throws Exception {
        List<Claim> lstBase = claims(FixtureTest.bodies(SOURCE).get(0));
        String ref = firstRef(lstBase, "status", "CONFIRMED");
        List<Claim> lstWithout = new ArrayList<>();
        for (Claim claim : lstBase) {
            if (!claim.subjectRef().equals(ref)) {
                lstWithout.add(claim);
            }
        }
        Events.Result res = Events.derive(SOURCE,
                List.of(snap("a", 0, lstBase), snap("b", 1, lstWithout), snap("c", 2, lstWithout)));
        Events.Event evt = event(res, ref);
        assertEquals(2, evt.revision(), "a second body without the record withdraws nothing more");
        assertEquals("true", evt.mapField().get("withdrawn"));
        assertEquals("CONFIRMED", evt.mapField().get("status"), "withdrawn is not cancelled");
        assertEquals("a", evt.lastObservationId(), "the last body that carried it");
        assertEquals("EVENT_WITHDRAWN", res.lstRecord().get(res.lstRecord().size() - 1).changeType());

        Events.Result resBack = Events.derive(SOURCE,
                List.of(snap("a", 0, lstBase), snap("b", 1, lstWithout), snap("c", 2, lstBase)));
        Events.Event evtBack = event(resBack, ref);
        assertEquals(3, evtBack.revision());
        assertEquals("false", evtBack.mapField().get("withdrawn"));
        assertEquals("EVENT_CORRECTED", resBack.lstRecord().get(resBack.lstRecord().size() - 1).changeType());
    }


    @Test
    void theIndexHoldsOneEventPerRecordAfterAPass() throws Exception {
        List<Path> lstBody = FixtureTest.bodies(SOURCE);
        Path dirEvidence = dirTmp.resolve("evidence");
        SourceDef def = new SourceDef(SOURCE, "test", "http", "http://127.0.0.1/schedule.json", null, null,
                "OFFICIAL", "none", 300, false, true);
        int cntRecord = refs(claims(lstBody.get(0))).size();
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:file:" + dirTmp.resolve("index") + ";DB_CLOSE_ON_EXIT=FALSE", "feed", "")) {
            Db.schema(conn);
            Sources.sync(conn, List.of(def));
            Instant instAt = Instant.parse("2026-09-07T08:47:34Z");
            for (Path fileBody : lstBody) {
                String keyStorage = Evidence.put(dirEvidence, Files.readAllBytes(fileBody));
                String shaBody = keyStorage.substring("sha256/".length());
                String idObs = Observations.insert(conn, SOURCE, new Banked(keyStorage, shaBody, "application/json",
                        shaBody, null, Integer.valueOf(200), null, null), instAt, "test");
                Observations.attempt(conn, SOURCE, "CHANGED", instAt, 10L, null, idObs);
                Observations.attempt(conn, SOURCE, "UNCHANGED", instAt.plusSeconds(300), 10L, null, null);
                instAt = instAt.plusSeconds(86400);
            }
            Normalize.pass(conn, dirEvidence);
            assertEquals((long) cntRecord, count(conn, "select count(*) from event"));
            assertEquals((long) cntRecord, count(conn, "select count(*) from event_revision"));
            assertEquals((long) cntRecord, count(conn, "select count(*) from event_change"));
            assertEquals(0L, count(conn, "select count(*) from event where revision <> 1 or withdrawn"));

            Normalize.pass(conn, dirEvidence);
            assertEquals((long) cntRecord, count(conn, "select count(*) from event_change"),
                    "a pass that reads nothing new changes nothing");
        }
    }


    private static List<Claim> claims(Path fileBody) throws Exception {
        return new ScheduleNormalizer().normalize(Files.readAllBytes(fileBody));
    }


    private static List<String> refs(List<Claim> lstClaim) {
        List<String> lstOut = new ArrayList<>();
        for (Claim claim : lstClaim) {
            if (!lstOut.contains(claim.subjectRef())) {
                lstOut.add(claim.subjectRef());
            }
        }
        return lstOut;
    }


    private static String firstRef(List<Claim> lstClaim, String field, String value) {
        for (Claim claim : lstClaim) {
            if (claim.field().equals(field) && value.equals(claim.value()))
                return claim.subjectRef();
        }
        throw new AssertionError("no record with " + field + " " + value + " in the banked body");
    }


    private static List<Claim> replace(List<Claim> lstClaim, String ref, String field, String value) {
        List<Claim> lstOut = new ArrayList<>();
        for (Claim claim : lstClaim) {
            if (claim.subjectRef().equals(ref) && claim.field().equals(field)) {
                lstOut.add(new Claim(claim.subjectRef(), claim.subjectNetwork(), claim.subjectKind(),
                        claim.subjectPeriod(), claim.field(), value, claim.precision(), claim.raw(),
                        claim.confidence()));
            }
            else {
                lstOut.add(claim);
            }
        }
        return lstOut;
    }


    private static Events.Snapshot snap(String idObs, int cntDay, List<Claim> lstClaim) {
        return new Events.Snapshot(idObs, Instant.parse("2026-09-07T08:47:34Z").plusSeconds(86400L * cntDay),
                lstClaim);
    }


    private static Events.Event event(Events.Result res, String ref) {
        for (Events.Event evt : res.lstEvent()) {
            if (evt.subjectRef().equals(ref))
                return evt;
        }
        throw new AssertionError("no event for " + ref);
    }


    private static long count(Connection conn, String sqlCount) throws Exception {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlCount)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
