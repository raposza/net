/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The three DSO documents over their banked bodies: the hosts each Super
 * Validator node publishes, and the join that puts them beside the roster's
 * version of the same node.
 *
 * Author Claude/bentzn
 */
class DsoSourcesTest {

    private static final String SRC_DSO_MAIN = "sync-global-dso-mainnet";

    private static final String SRC_DSO_TEST = "sync-global-dso-testnet";

    private static final Instant INST_NOW = Instant.parse("2026-10-07T00:00:00Z");


    @Test
    void aNodeInAnUpgradeCarriesBothSequencersHighestSerialFirst() throws Exception {
        List<Claim> lstClaim = new DsoNormalizer(SRC_DSO_TEST, "TESTNET").normalize(body(SRC_DSO_TEST));
        assertEquals("3=https://sequencer-3.sv-1.test.global.canton.network.sync.global"
                + " 2=https://sequencer-2.sv-1.test.global.canton.network.sync.global",
                valueOf(lstClaim, "Global-Synchronizer-Foundation", "sequencers"));
        assertEquals("https://scan.sv-1.test.global.canton.network.sync.global",
                valueOf(lstClaim, "Global-Synchronizer-Foundation", "scan_url"));
    }


    /**
     * The node's own sequencer field lags on one TestNet node, still naming
     * serial 2 while serial 3 is listed. The per-serial list is what is read.
     */
    @Test
    void theSequencersComeFromThePerSerialListAndNotTheLaggingField() throws Exception {
        List<Claim> lstClaim = new DsoNormalizer(SRC_DSO_TEST, "TESTNET").normalize(body(SRC_DSO_TEST));
        assertEquals("3=https://sequencer-3.sv.test.global.canton.network.tradeweb.com"
                + " 2=https://sequencer-2.sv.test.global.canton.network.tradeweb.com",
                valueOf(lstClaim, "Tradeweb-Markets-1", "sequencers"));
    }


    @Test
    void aBodyForAnotherNetworkIsRefused() throws Exception {
        byte[] bytesMain = body(SRC_DSO_MAIN);
        assertThrows(Normalizer.Refused.class,
                () -> new DsoNormalizer("sync-global-dso-devnet", "DEVNET").normalize(bytesMain));
    }


    @Test
    void aTruncatedBodyIsRefusedNotHalfRead() throws Exception {
        byte[] bytesCut = Arrays.copyOf(body(SRC_DSO_MAIN), 4000);
        assertThrows(Normalizer.Refused.class,
                () -> new DsoNormalizer(SRC_DSO_MAIN, "MAINNET").normalize(bytesCut));
    }


    @Test
    void anErrorPageIsRefused() {
        byte[] bytesBad = "<html><body>502 Bad Gateway</body></html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(Normalizer.Refused.class,
                () -> new DsoNormalizer(SRC_DSO_MAIN, "MAINNET").normalize(bytesBad));
    }


    @Test
    void theClaimedFormIsPublishedAsOneRecordPerSerial() {
        List<Object> lstOut = Events.sequencers("5=https://a.example 3=https://b.example");
        assertEquals(2, lstOut.size());
        assertEquals(Map.of("serial", "5", "url", "https://a.example"), lstOut.get(0));
        assertEquals(Map.of("serial", "3", "url", "https://b.example"), lstOut.get(1));
        assertNull(Events.sequencers(null));
        assertNull(Events.sequencers(" "));
    }


    /**
     * The roster is primary and keeps its version and scan url; the DSO document
     * fills in the sequencers. Every TestNet node is in an upgrade on the banked
     * body, so every one publishes two.
     */
    @Test
    void theRosterAndTheDsoDocumentArePublishedAsOneNode() throws Exception {
        List<Object> lstEvent = new ArrayList<>();
        lstEvent.addAll(fromSource(new SvVersionsNormalizer("sync-global-sv-versions-testnet", "TESTNET")));
        lstEvent.addAll(fromSource(new DsoNormalizer(SRC_DSO_TEST, "TESTNET")));
        List<Object> lstNetwork = Networks.derive(Events.joined(lstEvent), INST_NOW, "pub_test",
                "2026-10-07T00:00:00Z");
        Map<String, Object> mapTestnet = null;
        for (Object objNetwork : lstNetwork) {
            if ("TESTNET".equals(cast(objNetwork).get("network"))) {
                mapTestnet = cast(objNetwork);
            }
        }
        assertNotNull(mapTestnet);
        List<?> lstNode = (List<?>) mapTestnet.get("superValidators");
        assertEquals(13, lstNode.size(), "one node per name, however many sources describe it");
        for (Object objNode : lstNode) {
            Map<String, Object> mapNode = cast(objNode);
            assertNotNull(mapNode.get("version"), mapNode.get("name") + " lost the roster's version");
            assertNotNull(mapNode.get("scan"), mapNode.get("name") + " has no scan url");
            List<?> lstSeq = (List<?>) mapNode.get("sequencers");
            assertNotNull(lstSeq, mapNode.get("name") + " has no sequencers");
            assertEquals(2, lstSeq.size(), mapNode.get("name") + " is in an upgrade and carries two");
            assertEquals("3", cast(lstSeq.get(0)).get("serial"));
        }
    }


    private static List<Object> fromSource(Normalizer norm) throws Exception {
        List<Events.Snapshot> lstSnapshot = new ArrayList<>();
        Instant instAt = Instant.parse("2026-09-19T09:00:00Z");
        for (Path fileBody : FixtureTest.bodies(norm.sourceId())) {
            lstSnapshot.add(new Events.Snapshot(fileBody.getFileName().toString(), instAt,
                    norm.normalize(Files.readAllBytes(fileBody))));
            instAt = instAt.plusSeconds(86400L);
        }
        return Events.published(norm.sourceId(), norm.id(), Events.derive(norm.sourceId(), lstSnapshot).lstEvent());
    }


    private static byte[] body(String idSource) throws Exception {
        return Files.readAllBytes(FixtureTest.bodies(idSource).get(0));
    }


    private static String valueOf(List<Claim> lstClaim, String ref, String field) {
        for (Claim claim : lstClaim) {
            if (ref.equals(claim.subjectRef()) && field.equals(claim.field()))
                return claim.value();
        }
        throw new IllegalStateException("no claim " + ref + "/" + field);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object objAny) {
        return (Map<String, Object>) objAny;
    }
}
