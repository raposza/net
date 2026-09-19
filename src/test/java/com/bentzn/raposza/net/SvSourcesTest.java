/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The two sources that report what a network is RUNNING, over their real banked
 * bodies.
 *
 * Every refusal here is a control that can fail: each one feeds a body that is
 * wrong in one specific way and requires the normalizer to refuse it whole. A
 * parser that reads half a roster is worse than one that reads none, because
 * the half it read looks like an answer.
 *
 * Author Claude/bentzn
 */
class SvSourcesTest {

    private static final String SRC_INFO_MAIN = "sync-global-info-mainnet";

    private static final String SRC_INFO_DEV = "sync-global-info-devnet";

    private static final String SRC_SV_MAIN = "sync-global-sv-versions-mainnet";

    private static final String SRC_SV_DEV = "sync-global-sv-versions-devnet";


    @Test
    void theDeploymentInfoReadsWhatTheNetworkReports() throws Exception {
        List<Claim> lstClaim = info(SRC_INFO_MAIN, "MAINNET", "mainnet").normalize(body(SRC_INFO_MAIN));
        assertEquals("0.7.5", value(lstClaim, "version"));
        assertEquals("PATCH", precision(lstClaim, "version"));
        assertEquals("0.7.5", value(lstClaim, "sv_version"));
        assertEquals("4", value(lstClaim, "migration_id"));
        assertEquals("5", value(lstClaim, "serial_id"),
                "the serial id has moved past the frozen migration id on this network");
        assertEquals("2", value(lstClaim, "chain_id_suffix"));
        assertEquals(null, value(lstClaim, "successor_version"), "no migration is staged");
        assertEquals(null, value(lstClaim, "legacy_version"));
        for (Claim claim : lstClaim) {
            assertEquals("NETWORK_DEPLOYMENT", claim.subjectKind());
            assertEquals("MAINNET", claim.subjectNetwork());
        }
    }


    @Test
    void aBodyForAnotherNetworkIsRefused() throws Exception {
        byte[] bytesMain = body(SRC_INFO_MAIN);
        Normalizer normDev = info(SRC_INFO_DEV, "DEVNET", "devnet");
        Normalizer.Refused exRefused =
                assertThrows(Normalizer.Refused.class, () -> normDev.normalize(bytesMain));
        assertTrue(exRefused.getMessage().contains("pinned to devnet"), exRefused.getMessage());
    }


    @Test
    void aTruncatedDeploymentBodyIsRefusedNotHalfRead() throws Exception {
        byte[] bytesCut = Arrays.copyOf(body(SRC_INFO_MAIN), 60);
        assertThrows(Normalizer.Refused.class,
                () -> info(SRC_INFO_MAIN, "MAINNET", "mainnet").normalize(bytesCut));
    }


    @Test
    void theRosterReadsEveryNodeWithItsScanUrl() throws Exception {
        List<Claim> lstClaim = new SvVersionsNormalizer(SRC_SV_MAIN, "MAINNET").normalize(body(SRC_SV_MAIN));
        Set<String> setNode = new HashSet<>();
        for (Claim claim : lstClaim) {
            setNode.add(claim.subjectRef());
            assertEquals("SV_NODE", claim.subjectKind());
            assertEquals("MAINNET", claim.subjectNetwork());
        }
        assertTrue(setNode.size() >= 10, "the banked MainNet roster holds " + setNode.size() + " nodes");
        assertTrue(setNode.contains("Global-Synchronizer-Foundation"));
        assertEquals("0.7.5", valueOf(lstClaim, "Global-Synchronizer-Foundation", "version"));
        assertEquals("https://scan.sv-1.global.canton.network.sync.global",
                valueOf(lstClaim, "Global-Synchronizer-Foundation", "scan_url"));
    }


    @Test
    void oneNetworkIsNotOneVersion() throws Exception {
        List<Claim> lstClaim = new SvVersionsNormalizer(SRC_SV_DEV, "DEVNET").normalize(body(SRC_SV_DEV));
        Set<String> setVersion = new HashSet<>();
        for (Claim claim : lstClaim) {
            if ("version".equals(claim.field()) && claim.value() != null) {
                setVersion.add(claim.value());
            }
        }
        assertTrue(setVersion.size() > 1,
                "the banked DevNet roster is not uniform, which is why a per-network figure is not enough: "
                        + setVersion);
        assertEquals("0.8.3", value(info(SRC_INFO_DEV, "DEVNET", "devnet").normalize(body(SRC_INFO_DEV)),
                "version"), "and the network-wide figure is only one of them");
    }


    @Test
    void aRosterInAnotherOrderGivesTheSameClaims() throws Exception {
        String textBody = new String(body(SRC_SV_MAIN), StandardCharsets.UTF_8);
        List<String> lstLine = new ArrayList<>(List.of(textBody.split("\n")));
        List<String> lstRow = new ArrayList<>(lstLine.subList(1, lstLine.size()));
        java.util.Collections.reverse(lstRow);
        StringBuilder sbBody = new StringBuilder(lstLine.get(0)).append('\n');
        for (String lineOne : lstRow) {
            sbBody.append(lineOne).append('\n');
        }
        Normalizer norm = new SvVersionsNormalizer(SRC_SV_MAIN, "MAINNET");
        assertEquals(Claim.lines(norm.normalize(body(SRC_SV_MAIN))),
                Claim.lines(norm.normalize(sbBody.toString().getBytes(StandardCharsets.UTF_8))),
                "a body upstream merely re-ordered states the same facts");
    }


    @Test
    void aChangedHeaderIsRefused() {
        byte[] bytesBad = "SV Name, Scan URL, Release\nA, https://a, 0.1.0\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(Normalizer.Refused.class,
                () -> new SvVersionsNormalizer(SRC_SV_MAIN, "MAINNET").normalize(bytesBad));
    }


    @Test
    void aRowWithTheWrongNumberOfFieldsIsRefused() {
        byte[] bytesBad = "SV Name, Scan URL, Version\nA, https://a\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(Normalizer.Refused.class,
                () -> new SvVersionsNormalizer(SRC_SV_MAIN, "MAINNET").normalize(bytesBad));
    }


    @Test
    void anErrorPageServedAsTextIsRefused() {
        byte[] bytesBad = "<html><body>502 Bad Gateway</body></html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(Normalizer.Refused.class,
                () -> new SvVersionsNormalizer(SRC_SV_MAIN, "MAINNET").normalize(bytesBad));
    }


    @Test
    void everyNewSourceIsRegisteredAndHasANormalizer() throws Exception {
        for (String idSource : List.of(SRC_INFO_MAIN, SRC_INFO_DEV, SRC_SV_MAIN, SRC_SV_DEV,
                "sync-global-info-testnet", "sync-global-sv-versions-testnet")) {
            assertNotNull(Normalize.forSource(idSource), idSource + " has no normalizer");
            SourceDef def = null;
            for (SourceDef defOne : Sources.load()) {
                if (idSource.equals(defOne.id())) {
                    def = defOne;
                }
            }
            assertNotNull(def, idSource + " is not in the source registry");
            assertEquals("http", def.transport());
            assertTrue(def.enabled(), idSource + " is registered but not enabled");
        }
    }


    private static Normalizer info(String idSource, String nameNetwork, String nameUpstream) {
        return new NetworkInfoNormalizer(idSource, nameNetwork, nameUpstream);
    }


    private static byte[] body(String idSource) throws Exception {
        List<Path> lstBody = FixtureTest.bodies(idSource);
        assertEquals(1, lstBody.size(), "one banked body is expected for " + idSource);
        return Files.readAllBytes(lstBody.get(0));
    }


    private static String value(List<Claim> lstClaim, String nameField) {
        for (Claim claim : lstClaim) {
            if (nameField.equals(claim.field()))
                return claim.value();
        }
        throw new IllegalStateException("no claim for " + nameField);
    }


    private static String precision(List<Claim> lstClaim, String nameField) {
        for (Claim claim : lstClaim) {
            if (nameField.equals(claim.field()))
                return claim.precision();
        }
        throw new IllegalStateException("no claim for " + nameField);
    }


    private static String valueOf(List<Claim> lstClaim, String nameRef, String nameField) {
        for (Claim claim : lstClaim) {
            if (nameRef.equals(claim.subjectRef()) && nameField.equals(claim.field()))
                return claim.value();
        }
        throw new IllegalStateException("no claim for " + nameRef + "/" + nameField);
    }
}
