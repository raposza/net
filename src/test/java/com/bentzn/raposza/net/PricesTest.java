/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CC/USD price record. The voted rate is read from the banked DSO bodies;
 * the venues' readers wait for banked tickers of their own, so what is tested of
 * them here is only what happens when a body does not read.
 *
 * Author Claude/bentzn
 */
class PricesTest {

    private static final Instant INST_ROUND = Instant.parse("2026-10-16T08:00:00Z");

    @TempDir
    Path dirTmp;


    @Test
    void theVotedRateIsTheAmuletPriceOfTheOpenMiningRound() throws Exception {
        Prices.Quote quote = Prices.parse("dso", body("sync-global-dso-mainnet"));
        assertEquals("0.129521", quote.last());
        assertEquals("116255", quote.round());
        assertEquals("2026-10-06T16:30:01.115400Z", quote.sourceTime());
        assertNull(quote.bid(), "a voted rate has no bid");
        assertNull(quote.ask(), "a voted rate has no ask");
        assertEquals("0.129521", quote.nodeRaw().get("amuletPrice").asText(),
                "the raw part kept is the payload the rate was read from");
    }


    @Test
    void everyNetworkVotesItsOwnRate() throws Exception {
        assertEquals("0.129341", Prices.parse("dso", body("sync-global-dso-testnet")).last());
        assertEquals("0.129372", Prices.parse("dso", body("sync-global-dso-devnet")).last());
    }


    @Test
    void aBodyThatDoesNotReadIsKeptAsUnparsedWithWhatCameBack() throws Exception {
        Map<String, Object> mapReading = Prices.reading(def("dso-mainnet"), new Prices.Fetched(200, "{}", null, 5L),
                INST_ROUND);
        assertEquals("UNPARSED", mapReading.get("status"));
        assertEquals("{}", mapReading.get("rawText"));
        assertNull(mapReading.get("last"));
    }


    /** A body with content after the document is not read at all, not read half. */
    @Test
    void contentAfterTheDocumentIsRefused() throws Exception {
        Map<String, Object> mapReading = Prices.reading(def("okx-cc-usdt"),
                new Prices.Fetched(200, "{\"code\":\"0\",\"data\":[]} {}", null, 5L), INST_ROUND);
        assertEquals("UNPARSED", mapReading.get("status"));
    }


    @Test
    void aRefusalAndAnUnreachableHostAreReadingsToo() throws Exception {
        Map<String, Object> mapRefused = Prices.reading(def("kraken-cc-usd"),
                new Prices.Fetched(503, "busy", null, 5L), INST_ROUND);
        assertEquals("HTTP_ERROR", mapRefused.get("status"));
        assertEquals(Integer.valueOf(503), mapRefused.get("httpStatus"));
        Map<String, Object> mapDown = Prices.reading(def("okx-cc-usdt"),
                new Prices.Fetched(-1, null, "connect timed out", 5L), INST_ROUND);
        assertEquals("TRANSPORT_ERROR", mapDown.get("status"));
        assertNull(mapDown.get("httpStatus"));
    }


    /**
     * One round in which MainNet answers and nothing else does, then one in which
     * nothing answers: the published MainNet rate is still the one that was read,
     * beside the attempt that failed, and every attempt is a line of the record.
     */
    @Test
    void theLatestSuccessSurvivesALaterFailureAndEveryAttemptIsRecorded() throws Exception {
        List<Prices.PriceDef> lstDef = Prices.load();
        String textMain = body("sync-global-dso-mainnet");
        String urlMain = def("dso-mainnet").url();
        List<Map<String, Object>> lstFirst = Prices.round(lstDef, INST_ROUND, url -> CompletableFuture
                .completedFuture(url.equals(urlMain) ? new Prices.Fetched(200, textMain, null, 5L)
                        : new Prices.Fetched(503, "busy", null, 5L)));
        Prices.record(dirTmp, lstFirst, INST_ROUND);
        Instant instSecond = INST_ROUND.plusSeconds(900);
        List<Map<String, Object>> lstSecond = Prices.round(lstDef, instSecond,
                url -> CompletableFuture.completedFuture(new Prices.Fetched(-1, null, "down", 5L)));
        Prices.record(dirTmp, lstSecond, instSecond);

        List<String> lstLine = Files.readAllLines(dirTmp.resolve("readings.jsonl"), StandardCharsets.UTF_8);
        assertEquals(lstDef.size() * 2, lstLine.size());

        Map<String, Object> mapPub = Prices.publication(dirTmp, true, 900);
        assertEquals(Boolean.TRUE, mapPub.get("collecting"));
        assertEquals("2026-10-16T08:15:00Z", mapPub.get("polledAt"));
        Map<String, Object> mapMain = entry(mapPub, "dso-mainnet");
        assertEquals("0.129521", mapMain.get("last"));
        assertEquals("2026-10-16T08:00:00Z", mapMain.get("readAt"));
        assertEquals("TRANSPORT_ERROR", mapMain.get("lastAttemptStatus"));
        assertEquals("2026-10-16T08:15:00Z", mapMain.get("lastAttemptAt"));
        Map<String, Object> mapKraken = entry(mapPub, "kraken-cc-usd");
        assertNull(mapKraken.get("last"), "a venue never read publishes no price");
        assertEquals("USD", mapKraken.get("quote"));

        Files.delete(dirTmp.resolve("latest.json"));
        Map<String, Object> mapRebuilt = Prices.latest(dirTmp);
        assertEquals("2026-10-16T08:15:00Z", mapRebuilt.get("polledAt"),
                "latest.json is derived and rebuilds from the record");
        Prices.record(dirTmp, List.of(), instSecond);
        assertEquals("0.129521", entry(Prices.publication(dirTmp, true, 900), "dso-mainnet").get("last"));
    }


    @Test
    void theRegistryAloneCarriesEverySourceAndNoValue() throws Exception {
        Map<String, Object> mapReg = Prices.registryOnly();
        List<?> lstEntry = (List<?>) mapReg.get("prices");
        assertEquals(Prices.load().size(), lstEntry.size());
        Set<Object> setId = new HashSet<>();
        for (Object objOne : lstEntry) {
            Map<?, ?> mapOne = (Map<?, ?>) objOne;
            assertTrue(setId.add(mapOne.get("id")), "price ids are unique");
            assertNull(mapOne.get("last"));
            assertTrue(String.valueOf(mapOne.get("pair")).endsWith("/" + mapOne.get("quote")),
                    "the quote currency is the pair's own: " + mapOne.get("id"));
        }
        assertNull(mapReg.get("collecting"), "the registry alone does not know whether anything reads");
    }


    private static String body(String idSource) throws Exception {
        return Files.readString(FixtureTest.bodies(idSource).get(0), StandardCharsets.UTF_8);
    }


    private static Prices.PriceDef def(String idPrice) throws Exception {
        for (Prices.PriceDef def : Prices.load()) {
            if (def.id().equals(idPrice))
                return def;
        }
        throw new IllegalStateException("no price source " + idPrice);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Map<String, Object> mapPub, String idPrice) {
        for (Object objOne : (List<?>) mapPub.get("prices")) {
            Map<String, Object> mapOne = (Map<String, Object>) objOne;
            if (idPrice.equals(mapOne.get("id")))
                return mapOne;
        }
        assertNotNull(null, "no published entry " + idPrice);
        return null;
    }
}
