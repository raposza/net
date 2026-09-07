/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * The http transport against a server the test runs itself, so nothing here
 * reaches the network and nothing depends on what an upstream publisher happens
 * to be serving today.
 *
 * Author Claude/bentzn
 */
class HttpCollectorTest {

    @TempDir
    Path dirTmp;

    private HttpServer srv;

    private final AtomicReference<String> refBody = new AtomicReference<>("{\"a\":1}");

    private final AtomicReference<String> refEtag = new AtomicReference<>("\"v1\"");

    private final AtomicInteger cntStatus = new AtomicInteger(200);

    private final AtomicInteger cntRequest = new AtomicInteger(0);


    @BeforeEach
    void start() throws IOException {
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/d.json", exch -> {
            cntRequest.incrementAndGet();
            int statusWanted = cntStatus.get();
            String textMatch = exch.getRequestHeaders().getFirst("If-None-Match");
            if (statusWanted == 200 && textMatch != null && textMatch.equals(refEtag.get())) {
                exch.sendResponseHeaders(304, -1);
                exch.close();
                return;
            }
            byte[] bytesBody = refBody.get().getBytes(StandardCharsets.UTF_8);
            if (statusWanted != 200) {
                exch.sendResponseHeaders(statusWanted, -1);
                exch.close();
                return;
            }
            exch.getResponseHeaders().add("Content-Type", "application/json");
            exch.getResponseHeaders().add("ETag", refEtag.get());
            exch.sendResponseHeaders(200, bytesBody.length);
            try (OutputStream strmOut = exch.getResponseBody()) {
                strmOut.write(bytesBody);
            }
        });
        srv.start();
    }


    @AfterEach
    void stop() {
        srv.stop(0);
    }


    @Test
    void banksTheBodyOnceAndThenGoesQuiet() {
        SourceDef def = def();
        Path dirEvidence = dirTmp.resolve("evidence");

        Poll resFirst = HttpCollector.collect(def, dirEvidence, null);
        assertEquals("CHANGED", resFirst.outcome(), resFirst.detail());
        assertEquals(1, resFirst.lstBanked().size());
        Banked banked = resFirst.lstBanked().get(0);
        assertTrue(Evidence.has(dirEvidence, banked.keyStorage()));
        assertEquals("\"v1\"", banked.etag());
        assertEquals(Integer.valueOf(200), banked.httpStatus());
        assertTrue(banked.mediaType().startsWith("application/json"), banked.mediaType());
        assertEquals(banked.shaContent(), banked.revision());
        assertNull(banked.upstreamAt());

        Poll resSame = HttpCollector.collect(def, dirEvidence,
                new HttpCollector.State(banked.etag(), null, banked.shaContent()));
        assertEquals("UNCHANGED", resSame.outcome(), resSame.detail());
        assertTrue(resSame.lstBanked().isEmpty());
        assertTrue(resSame.detail().contains("304"), resSame.detail());
    }


    @Test
    void identicalBytesWithoutAValidatorAreStillUnchanged() {
        SourceDef def = def();
        Path dirEvidence = dirTmp.resolve("evidence");
        Poll resFirst = HttpCollector.collect(def, dirEvidence, null);
        String shaFirst = resFirst.lstBanked().get(0).shaContent();

        Poll resSame = HttpCollector.collect(def, dirEvidence,
                new HttpCollector.State(null, null, shaFirst));
        assertEquals("UNCHANGED", resSame.outcome(), resSame.detail());
        assertTrue(resSame.detail().contains("same body"), resSame.detail());
    }


    @Test
    void aChangedBodyIsANewObservation() {
        SourceDef def = def();
        Path dirEvidence = dirTmp.resolve("evidence");
        Poll resFirst = HttpCollector.collect(def, dirEvidence, null);
        Banked bankedFirst = resFirst.lstBanked().get(0);

        refBody.set("{\"a\":2}");
        refEtag.set("\"v2\"");
        Poll resNext = HttpCollector.collect(def, dirEvidence,
                new HttpCollector.State(bankedFirst.etag(), null, bankedFirst.shaContent()));
        assertEquals("CHANGED", resNext.outcome(), resNext.detail());
        assertTrue(Evidence.has(dirEvidence, resFirst.lstBanked().get(0).keyStorage()),
                "the earlier body is still there");
        assertTrue(Evidence.has(dirEvidence, resNext.lstBanked().get(0).keyStorage()));
    }


    @Test
    void aRefusalIsRecordedRatherThanThrown() {
        cntStatus.set(503);
        Poll res = HttpCollector.collect(def(), dirTmp.resolve("evidence"), null);
        assertEquals("HTTP_ERROR", res.outcome());
        assertTrue(res.detail().contains("503"), res.detail());
        assertTrue(res.lstBanked().isEmpty());
    }


    @Test
    void anUnreachableHostIsRecordedRatherThanThrown() {
        SourceDef def = new SourceDef("dead", "test", "http", "http://127.0.0.1:1/nothing",
                null, null, "COMMUNITY", "none", 300, false, true);
        Poll res = HttpCollector.collect(def, dirTmp.resolve("evidence"), null);
        assertEquals("TRANSPORT_ERROR", res.outcome());
        assertNotNull(res.detail());
        assertTrue(res.lstBanked().isEmpty());
    }


    private SourceDef def() {
        return new SourceDef("test-http", "test", "http",
                "http://127.0.0.1:" + srv.getAddress().getPort() + "/d.json",
                null, null, "OFFICIAL", "none", 300, false, true);
    }
}
