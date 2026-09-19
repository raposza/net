/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The published corpus over the real banked schedule bodies: the events as a
 * consumer receives them, the per-network summary derived from them, and the
 * round trip through the dataset directory.
 *
 * The events are derived from the fixtures rather than read from an index, so
 * this needs no database and no environment. What it proves is the contract and
 * the rules, which is what a consumer depends on; that the index returns the
 * same events is the concern of the derivation, which EventsTest covers.
 *
 * The moment is fixed. Every rule that decides a network summary compares a
 * scheduled date against the publication's date, so a test that used the wall
 * clock would change its answer as the calendar moved past the fixtures.
 *
 * Author Claude/bentzn
 */
class PublishTest {

    private static final String SOURCE = ScheduleNormalizer.SOURCE_ID;

    private static final Instant INST_NOW = Instant.parse("2026-09-18T00:00:00Z");

    @TempDir
    Path dirTmp;


    @Test
    void everyPublishedEventCarriesTheContractAndInventsNothing() throws Exception {
        List<Object> lstEvent = published();
        assertFalse(lstEvent.isEmpty(), "the fixtures produced no event");
        for (Object objEvent : lstEvent) {
            Map<String, Object> mapEvent = cast(objEvent);
            assertTrue(String.valueOf(mapEvent.get("id")).startsWith("evt_"));
            assertEquals(Integer.valueOf(1), mapEvent.get("schemaVersion"));
            assertNotNull(mapEvent.get("kind"), "an event without a kind is not publishable");
            assertNotNull(mapEvent.get("upstream"), "an event must say where upstream it came from");
            Map<String, Object> mapEffective = cast(mapEvent.get("effective"));
            assertEquals(mapEffective.get("from") == null ? "UNKNOWN" : "DATE",
                    mapEffective.get("precision"), "a precision is never manufactured");
            Map<String, Object> mapProvenance = cast(((List<?>) mapEvent.get("provenance")).get(0));
            assertEquals(SOURCE, mapProvenance.get("sourceId"));
            assertEquals(ScheduleNormalizer.ID, mapProvenance.get("normalizer"));
            assertNotNull(mapProvenance.get("observationId"));
        }
    }


    @Test
    void theEventOrderIsTotalAndRepeatable() throws Exception {
        assertEquals(published(), published(), "two publications of one index must be identical");
    }


    @Test
    void theNetworkSummaryComesFromTheSchedule() throws Exception {
        List<Object> lstNetwork = Networks.derive(published(), INST_NOW, "pub_test", "2026-09-18T00:00:00Z");
        assertEquals(Networks.LST_NETWORK.size(), lstNetwork.size());
        for (Object objNetwork : lstNetwork) {
            Map<String, Object> mapNetwork = cast(objNetwork);
            assertNull(mapNetwork.get("synchronizer"), "no source this feed reads reports a synchronizer");
            Map<String, Object> mapSplice = cast(mapNetwork.get("splice"));
            assertNull(mapSplice.get("currentVersion"), "the network record carries no currentVersion field");
            assertNotNull(mapSplice.get("scheduledVersion"), mapNetwork.get("network") + " has no scheduled version");
            assertNotNull(mapSplice.get("minimumVersion"), mapNetwork.get("network") + " has no minimum version");
        }
    }


    @Test
    void mainnetReadsWhatTheBankedScheduleActuallySays() throws Exception {
        Map<String, Object> mapNetwork = network("MAINNET");
        Map<String, Object> mapSplice = cast(mapNetwork.get("splice"));
        assertEquals("0.7.5", mapSplice.get("scheduledVersion"));
        assertEquals("2026-09-14", mapSplice.get("scheduledFrom"));
        assertEquals("0.7", mapSplice.get("minimumVersion"));
        assertEquals("MINOR", mapSplice.get("minimumPrecision"), "upstream states a minor, not a patch");
        Map<String, Object> mapNext = cast(mapNetwork.get("next"));
        assertEquals("0.8.0", mapNext.get("version"));
        assertEquals("2026-09-21", mapNext.get("from"));
        assertEquals("CONFIRMED", mapNext.get("status"));
    }


    @Test
    void theDatasetDirectoryRoundTripsAndStatesWhatItIsMadeOf() throws Exception {
        Map<String, Object> mapDs = Dataset.generate("pub_test", INST_NOW,
                new Dataset.Index(List.of(), published()));
        assertEquals("OBSERVED", Dataset.meta(mapDs).get("content"));
        Path dirDataset = dirTmp.resolve("dataset");
        Dataset.write(dirDataset, mapDs);
        Map<String, Object> mapBack = Dataset.read(dirDataset);
        assertNotNull(mapBack, "the dataset directory did not read back");
        assertEquals("OBSERVED", Dataset.meta(mapBack).get("content"));
        assertEquals(Networks.LST_NETWORK.size(), ((List<?>) mapBack.get("networks")).size());
        assertEquals(((List<?>) mapDs.get("events")).size(), ((List<?>) mapBack.get("events")).size());
    }


    @Test
    void anEmptyIndexAndAnUnreadableOneAreDifferentPublications() {
        assertEquals("EMPTY", Dataset.meta(Dataset.generate("pub_test", INST_NOW,
                new Dataset.Index(List.of(), List.of()))).get("content"));
        assertEquals("UNAVAILABLE", Dataset.meta(Dataset.generate("pub_test", INST_NOW, null)).get("content"));
        List<Object> lstNetwork = Networks.derive(List.of(), INST_NOW, "pub_test", "2026-09-18T00:00:00Z");
        assertEquals(Networks.LST_NETWORK.size(), lstNetwork.size(),
                "a network is published even when the schedule says nothing about it");
        assertNull(cast(cast(lstNetwork.get(0)).get("splice")).get("scheduledVersion"));
    }


    /**
     * `versions.yml` over the banked bodies at the fixed publication instant.
     * Every value asserted here is one this suite already derives from a fixture
     * elsewhere, line for line as the file writes it, so a normalizer change that
     * moves one of them fails here too. The scheduled block of TestNet and DevNet
     * is deliberately not pinned: what is ahead for those two moves with the
     * banked bodies and no other test states it.
     */
    @Test
    void thePublishedVersionsFileSaysWhatTheDataSays() throws Exception {
        String textYaml = Versions.yaml(withReleases());
        assertTrue(textYaml.startsWith("timestamp: 2026-09-18T00:00:00Z\nnetworks:\n"), textYaml);
        assertTrue(textYaml.contains("  mainnet:\n"
                + "    current: \"0.7.5\"\n"
                + "    sv-version: \"0.7.5\"\n"
                + "    minimum: \"0.7\"\n"
                + "    scheduled:\n"
                + "      date: 2026-09-21\n"
                + "      version: \"0.8.0\"\n"
                + "    migration-id: 4\n"
                + "    serial-id: 5\n"
                + "    chain-id-suffix: \"2\"\n"
                + "    successor: null\n"
                + "    legacy: null\n"), textYaml);
        assertTrue(textYaml.contains("      - name: \"Global-Synchronizer-Foundation\"\n"
                + "        version: \"0.7.5\"\n"
                + "        scan: \"https://scan.sv-1.global.canton.network.sync.global\"\n"), textYaml);
        assertTrue(textYaml.endsWith("splice-latest: \"0.8.3\"\n"), textYaml);
        assertTrue(textYaml.indexOf("  mainnet:") < textYaml.indexOf("  testnet:")
                && textYaml.indexOf("  testnet:") < textYaml.indexOf("  devnet:"),
                "the networks are published in one fixed order");
    }


    /**
     * The reason `current` stopped being read off the schedule. On the banked
     * bodies DevNet reports 0.8.3 while the schedule's latest arrived upgrade is
     * 0.8.1, so the published value and the planned one differ and the published
     * one is the observation.
     */
    @Test
    void currentIsWhatTheNetworkReportsAndNotWhatTheCalendarPlanned() throws Exception {
        Map<String, Object> mapDs = withReleases();
        Map<String, Object> mapDevnet = network(mapDs, "DEVNET");
        assertEquals("0.8.3", cast(mapDevnet.get("deployment")).get("version"));
        assertEquals("0.8.1", cast(mapDevnet.get("splice")).get("scheduledVersion"));
        assertTrue(Versions.yaml(mapDs).contains("  devnet:\n    current: \"0.8.3\"\n"),
                Versions.yaml(mapDs));
    }


    /**
     * One network is not one version. The banked DevNet roster carries two, which
     * is what a consumer connected to a single node has to be able to see.
     */
    @Test
    void theRosterIsPublishedPerNodeAndNeedNotBeUniform() throws Exception {
        Map<String, Object> mapDevnet = network(withReleases(), "DEVNET");
        List<?> lstNode = (List<?>) mapDevnet.get("superValidators");
        assertEquals(14, lstNode.size(), "every node in the banked roster is published");
        java.util.Set<String> setVersion = new java.util.TreeSet<>();
        for (Object objNode : lstNode) {
            setVersion.add(String.valueOf(cast(objNode).get("version")));
            assertNotNull(cast(objNode).get("scan"), "a node is published with the url it answers on");
        }
        assertEquals(java.util.Set.of("0.8.1", "0.8.3"), setVersion);
    }


    @Test
    void theHistoryEntryIsTheWholeVersionsFileReindented() throws Exception {
        String textYaml = Versions.yaml(withReleases());
        String textEntry = History.entry(textYaml);
        assertTrue(textEntry.startsWith("  - timestamp: 2026-09-18T00:00:00Z\n"), textEntry);
        assertTrue(textEntry.contains("\n    networks:\n"), textEntry);
        assertTrue(textEntry.contains("\n      mainnet:\n"), textEntry);
        assertTrue(textEntry.endsWith("\n    splice-latest: \"0.8.3\"\n"), textEntry);
        assertEquals(textYaml.split("\n").length, textEntry.split("\n").length,
                "an entry carries every line of the file and no other");
    }


    @Test
    void theTimestampIsTheOnlyLineTheChangeUnitIgnores() throws Exception {
        String textYaml = Versions.yaml(withReleases());
        String textCut = Versions.withoutTimestamp(textYaml);
        assertFalse(textCut.contains("timestamp:"), textCut);
        assertEquals(textYaml.length() - "timestamp: 2026-09-18T00:00:00Z\n".length(), textCut.length(),
                "nothing but that line is dropped");
    }


    @Test
    void theLatestReleaseIsComparedAsNumbersNotAsText() {
        assertTrue(Versions.compare("0.10.4", "0.8.2") > 0, "0.10.4 is above 0.8.2");
        assertTrue(Versions.compare("0.8", "0.8.1") < 0, "a minor is below a patch of it");
        assertEquals(0, Versions.compare("0.8.0", "0.8"), "a trailing zero is not a difference");
    }


    @Test
    void twoSourcesDescribingOneReleaseArePublishedOnce() throws Exception {
        List<Object> lstJoined = releases();
        Map<String, Object> mapFound = null;
        int cntSameRef = 0;
        for (Object objEvent : lstJoined) {
            Map<String, Object> mapEvent = cast(objEvent);
            if ("0.8.3".equals(cast(mapEvent.get("upstream")).get("ref"))) {
                mapFound = mapEvent;
                cntSameRef++;
            }
        }
        assertEquals(1, cntSameRef, "0.8.3 is one release however many sources describe it");
        assertNotNull(mapFound.get("commit_sha"), "the tags source carries the sha");
        assertEquals("2026-09-18T09:39:53Z", mapFound.get("commit_time"),
                "the notes source carries the time, normalized to UTC");
        assertEquals("0.8.3", cast(mapFound.get("version")).get("value"));
        assertEquals(2, ((List<?>) mapFound.get("provenance")).size(), "both sources are named");
        assertEquals("tag", cast(mapFound.get("upstream")).get("type"),
                "the tags source is primary, so its upstream type stands");
    }


    @Test
    void aReleaseOnlyTheTagsSourceHasIsStillPublishedWithNoTime() throws Exception {
        for (Object objEvent : releases()) {
            Map<String, Object> mapEvent = cast(objEvent);
            if ("0.4.2".equals(cast(mapEvent.get("upstream")).get("ref"))) {
                assertNotNull(mapEvent.get("commit_sha"));
                assertNull(mapEvent.get("commit_time"), "the notes window does not reach back this far");
                return;
            }
        }
        throw new IllegalStateException("0.4.2 is in the banked tags body and was not published");
    }


    @Test
    void aTagThatIsNotAVersionIsPublishedWithoutOne() throws Exception {
        int cntReview = 0;
        for (Object objEvent : releases()) {
            Map<String, Object> mapEvent = cast(objEvent);
            assertEquals("SOFTWARE_RELEASE", mapEvent.get("kind"));
            assertNull(cast(mapEvent.get("effective")).get("from"), "a release carries no scheduled date");
            assertNull(mapEvent.get("status"), "upstream states no status for a tag");
            if (cast(mapEvent.get("version")).get("value") == null) {
                cntReview++;
            }
        }
        assertTrue(cntReview > 0, "the banked body holds a tag that is not a version");
    }


    /**
     * The corpus as a publication carrying both the schedule events and the
     * releases, which is what `versions.yml` is reduced from.
     */
    private static Map<String, Object> withReleases() throws Exception {
        List<Object> lstEvent = new ArrayList<>(published());
        lstEvent.addAll(releases());
        lstEvent.addAll(reported());
        return Dataset.generate("pub_test", INST_NOW, new Dataset.Index(List.of(), lstEvent));
    }


    /**
     * What the three networks report they are running, and their rosters, from
     * the banked bodies of the six deployment sources.
     */
    private static List<Object> reported() throws Exception {
        List<Object> lstOut = new ArrayList<>();
        for (String[] arrNet : new String[][] {
                { "mainnet", "MAINNET" }, { "testnet", "TESTNET" }, { "devnet", "DEVNET" } }) {
            lstOut.addAll(fromSource(new NetworkInfoNormalizer("sync-global-info-" + arrNet[0], arrNet[1],
                    arrNet[0])));
            lstOut.addAll(fromSource(
                    new SvVersionsNormalizer("sync-global-sv-versions-" + arrNet[0], arrNet[1])));
        }
        return lstOut;
    }


    private static Map<String, Object> network(Map<String, Object> mapDs, String nameNetwork) {
        for (Object objNetwork : (List<?>) mapDs.get("networks")) {
            Map<String, Object> mapNetwork = cast(objNetwork);
            if (nameNetwork.equals(mapNetwork.get("network")))
                return mapNetwork;
        }
        throw new IllegalStateException("no network " + nameNetwork + " was published");
    }


    private Map<String, Object> network(String nameNetwork) throws Exception {
        for (Object objNetwork : Networks.derive(published(), INST_NOW, "pub_test", "2026-09-18T00:00:00Z")) {
            Map<String, Object> mapNetwork = cast(objNetwork);
            if (nameNetwork.equals(mapNetwork.get("network"))) {
                return mapNetwork;
            }
        }
        throw new IllegalStateException("no network " + nameNetwork + " was published");
    }


    private static List<Object> published() throws Exception {
        Normalizer norm = new ScheduleNormalizer();
        List<Events.Snapshot> lstSnapshot = new ArrayList<>();
        Instant instAt = Instant.parse("2026-09-07T08:47:34Z");
        for (Path fileBody : FixtureTest.bodies(SOURCE)) {
            lstSnapshot.add(new Events.Snapshot(fileBody.getFileName().toString(), instAt,
                    norm.normalize(Files.readAllBytes(fileBody))));
            instAt = instAt.plusSeconds(86400L);
        }
        return Events.published(SOURCE, ScheduleNormalizer.ID, Events.derive(SOURCE, lstSnapshot).lstEvent());
    }


    /**
     * The releases as they are published: both Splice sources, in the order
     * Normalize.all() declares, joined exactly as the index publication joins
     * them.
     */
    private static List<Object> releases() throws Exception {
        List<Object> lstOut = new ArrayList<>();
        lstOut.addAll(fromSource(new SpliceTagsNormalizer()));
        lstOut.addAll(fromSource(new SpliceReleaseNotesNormalizer()));
        return Events.joined(lstOut);
    }


    private static List<Object> fromSource(Normalizer norm) throws Exception {
        List<Events.Snapshot> lstSnapshot = new ArrayList<>();
        Instant instAt = Instant.parse("2026-09-07T08:47:35Z");
        for (Path fileBody : FixtureTest.bodies(norm.sourceId())) {
            lstSnapshot.add(new Events.Snapshot(fileBody.getFileName().toString(), instAt,
                    norm.normalize(Files.readAllBytes(fileBody))));
            instAt = instAt.plusSeconds(86400L);
        }
        return Events.published(norm.sourceId(), norm.id(),
                Events.derive(norm.sourceId(), lstSnapshot).lstEvent());
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object objAny) {
        return (Map<String, Object>) objAny;
    }
}
