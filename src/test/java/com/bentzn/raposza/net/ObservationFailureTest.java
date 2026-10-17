/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A node upstream could not ask is an observation failure, not a version
 * change: the roster row is read as a status, the event keeps its version, the
 * change log leaves the failure and its recovery out, and the roster counts say
 * how many nodes are in that state.
 *
 * Author Claude/bentzn
 */
class ObservationFailureTest {

    private static final String SOURCE = "sync-global-sv-versions-mainnet";

    private static final String FAILED = "Thu Oct 15 06:10:24 UTC 2026: Failed to fetch version";


    @Test
    void anUnreadableVersionIsAStatusAndNotAVersion() throws Exception {
        List<Claim> lstClaim = normalizer().normalize(body("A, https://scan.a, 0.9.1\nB, https://scan.b, " + FAILED));
        assertEquals("0.9.1", value(lstClaim, "A", "version"));
        assertNull(value(lstClaim, "A", "version_status"));
        assertNull(value(lstClaim, "B", "version"), "no version is claimed for a node upstream could not ask");
        assertEquals(SvVersionsNormalizer.STATUS_UNAVAILABLE, value(lstClaim, "B", "version_status"));
    }


    @Test
    void aNodeThatCouldNotBeAskedKeepsItsVersionAndOnlyItsStatusMoves() throws Exception {
        List<Claim> lstRead = normalizer().normalize(body("A, https://scan.a, 0.9.1"));
        List<Claim> lstFailed = normalizer().normalize(body("A, https://scan.a, " + FAILED));
        Events.Result res = Events.derive(SOURCE, List.of(snap("a", 0, lstRead), snap("b", 1, lstFailed),
                snap("c", 2, lstRead)));

        assertEquals(1, res.lstEvent().size());
        Events.Event evt = res.lstEvent().get(0);
        assertEquals("0.9.1", evt.mapField().get("version"));
        assertNull(evt.mapField().get("version_status"));
        assertEquals(3, evt.revision());

        Events.Revision revFailed = res.lstRevision().get(1);
        Events.Revision revBack = res.lstRevision().get(2);
        assertTrue(Timeline.isStatusOnly(fields(revFailed)), "the failure moves the status alone");
        assertTrue(Timeline.isStatusOnly(fields(revBack)), "the recovery moves the status alone");

        Events.Result resMoved = Events.derive(SOURCE, List.of(snap("a", 0, lstRead), snap("b", 1, lstFailed),
                snap("c", 2, normalizer().normalize(body("A, https://scan.a, 0.9.2")))));
        Events.Revision revNew = resMoved.lstRevision().get(2);
        assertTrue(!Timeline.isStatusOnly(fields(revNew)), "a node that comes back on a new version has changed");
        assertEquals("0.9.2", resMoved.lstEvent().get(0).mapField().get("version"));
    }


    @Test
    void theRosterSaysHowManyNodesAreOnTheScheduledVersion() {
        List<Object> lstNode = List.of(node("A", "0.9.1", null), node("B", "0.9.1", null), node("C", "0.9.0", null),
                node("D", "0.9.1", SvVersionsNormalizer.STATUS_UNAVAILABLE), node("E", null, null));
        Map<String, Object> mapRoster = Networks.roster(lstNode, "0.9.1");
        assertEquals(Integer.valueOf(5), mapRoster.get("nodes"));
        assertEquals(Integer.valueOf(3), mapRoster.get("onScheduled"));
        assertEquals(Integer.valueOf(1), mapRoster.get("unreported"));
        assertEquals(Integer.valueOf(1), mapRoster.get("unavailable"));
        assertEquals(List.of(Dataset.map("version", "0.9.1", "count", Integer.valueOf(3)),
                Dataset.map("version", "0.9.0", "count", Integer.valueOf(1))), mapRoster.get("versions"));
    }


    private static SvVersionsNormalizer normalizer() {
        return new SvVersionsNormalizer(SOURCE, "MAINNET");
    }


    private static byte[] body(String textRows) {
        return ("SV Name, Scan URL, Version\n" + textRows + "\n").getBytes(StandardCharsets.UTF_8);
    }


    private static String value(List<Claim> lstClaim, String ref, String field) {
        for (Claim claim : lstClaim) {
            if (claim.subjectRef().equals(ref) && claim.field().equals(field))
                return claim.value();
        }
        return null;
    }


    private static Events.Snapshot snap(String idObs, int cntDay, List<Claim> lstClaim) {
        return new Events.Snapshot(idObs, Instant.parse("2026-10-01T08:00:00Z").plusSeconds(86400L * cntDay),
                lstClaim);
    }


    private static List<Object> fields(Events.Revision rev) {
        List<Object> lstOut = new ArrayList<>();
        for (Events.Change chg : rev.lstChange()) {
            lstOut.add(Dataset.map("field", chg.field(), "old", chg.valueOld(), "new", chg.valueNew()));
        }
        return lstOut;
    }


    private static Map<String, Object> node(String name, String version, String status) {
        return Dataset.map("name", name, "version", version, "versionStatus", status);
    }
}
