/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The http transport: one GET, one body, at most one observation.
 *
 * Unchanged is established three ways, cheapest first. A conditional request
 * carrying the previous validators may be answered 304, in which case no body
 * crosses the network at all. Otherwise the body is hashed and compared with the
 * last one banked. Only bytes that differ become an observation.
 *
 * Nothing here interprets the body. A JSON document, an HTML page and a calendar
 * feed are the same thing to this collector: bytes, with a media type recorded
 * beside them. Interpretation is a normalizer's job, later, against evidence
 * that is already banked.
 *
 * Author Claude/bentzn
 */
public final class HttpCollector {

    /** Recorded on every observation; change it when the retrieval behaviour changes. */
    public static final String VER_COLLECTOR = "HttpCollector@1.0";

    private static final String AGENT = "raposza-network-feed";

    private static final Duration DUR_TIMEOUT = Duration.ofSeconds(60);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();


    private HttpCollector() {
    }


    /**
     * What the last observation of this source said, so the next request can be
     * conditional. All three fields may be null, which means retrieve
     * unconditionally.
     */
    public record State(String etag, String lastModified, String shaContent) {
    }


    /**
     * Retrieves one source.
     *
     * Never throws: an unreachable host, a refusal and a redirect loop are all
     * recorded outcomes, because a source that is down is data about the source.
     *
     * @param def the source definition
     * @param dirEvidence root of the evidence store
     * @param state what the previous observation carried, or null to bootstrap
     * @return what happened, and the observation if the bytes were new
     */
    public static Poll collect(SourceDef def, Path dirEvidence, State state) {
        long msStart = System.currentTimeMillis();
        try {
            HttpResponse<byte[]> resp = CLIENT.send(request(def, state), HttpResponse.BodyHandlers.ofByteArray());
            int statusHttp = resp.statusCode();
            if (statusHttp == 304)
                return Poll.of("UNCHANGED", "304 not modified", millis(msStart),
                        state == null ? null : state.shaContent());
            if (statusHttp < 200 || statusHttp > 299)
                return Poll.of("HTTP_ERROR", "status " + statusHttp, millis(msStart), null);

            byte[] bytesBody = resp.body();
            String keyStorage = Evidence.key(bytesBody);
            String shaContent = keyStorage.substring("sha256/".length());
            if (state != null && shaContent.equals(state.shaContent()))
                return Poll.of("UNCHANGED", "same body, " + bytesBody.length + " bytes",
                        millis(msStart), shaContent);

            Evidence.put(dirEvidence, bytesBody);
            Banked banked = new Banked(keyStorage, shaContent,
                    header(resp, "content-type"), shaContent, null,
                    Integer.valueOf(statusHttp), header(resp, "etag"), header(resp, "last-modified"));
            return new Poll("CHANGED", bytesBody.length + " bytes", millis(msStart),
                    shaContent, List.of(banked));
        }
        catch (IOException | IllegalArgumentException e) {
            return Poll.of("TRANSPORT_ERROR", String.valueOf(e.getMessage()), millis(msStart), null);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Poll.of("TRANSPORT_ERROR", "interrupted", millis(msStart), null);
        }
    }


    private static HttpRequest request(SourceDef def, State state) {
        HttpRequest.Builder bldReq = HttpRequest.newBuilder(URI.create(def.url()))
                .GET()
                .timeout(DUR_TIMEOUT)
                .header("User-Agent", AGENT)
                .header("Accept", "*/*");
        if (state != null && state.etag() != null && !state.etag().isBlank()) {
            bldReq.header("If-None-Match", state.etag());
        }
        if (state != null && state.lastModified() != null && !state.lastModified().isBlank()) {
            bldReq.header("If-Modified-Since", state.lastModified());
        }
        return bldReq.build();
    }


    private static String header(HttpResponse<byte[]> resp, String nameHeader) {
        Optional<String> valHeader = resp.headers().firstValue(nameHeader);
        return valHeader.isPresent() && !valHeader.get().isBlank() ? valHeader.get() : null;
    }


    private static long millis(long msStart) {
        return System.currentTimeMillis() - msStart;
    }
}
