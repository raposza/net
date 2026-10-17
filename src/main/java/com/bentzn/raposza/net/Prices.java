/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The CC/USD rate: the price each network votes into its open mining round, and
 * the price the trading venues quote, read on a fixed interval and kept.
 *
 * A price is a time series, not a state. A ticker body differs on every poll,
 * so the evidence store, which banks a body only when its bytes say something
 * new, is the wrong place for it. Every reading is kept instead, as one line of
 * its own record, readings.jsonl, appended and forced to disk; a failed reading
 * is kept too, because a venue that did not answer is data about the venue.
 * Each line carries the part of the body the values were read from - the whole
 * ticker, or the payload of the open mining round - so a reading can be checked
 * against what was retrieved without the record carrying the whole DSO document
 * every time.
 *
 * Beside it latest.json holds, per price source, the newest reading that
 * succeeded and the newest attempt. It is derived: when it is missing it is
 * rebuilt from readings.jsonl.
 *
 * Values are published exactly as the source gives them, as text. A quote in
 * USDT or USDC is a quote in that currency and is never presented as USD.
 *
 * The definitions are prices.json in the artifact, for the same reason as the
 * source registry: what is read is a reviewed change, never a discovery.
 *
 * Author Claude/bentzn
 */
public final class Prices {

    /** Recorded on every reading; change it when the reading behaviour changes. */
    public static final String VER_READER = "Prices@1";

    private static final String RES_PRICES = "/prices.json";

    private static final String NAME_READINGS = "readings.jsonl";

    private static final String NAME_LATEST = "latest.json";

    private static final String AGENT = "raposza-network-feed";

    private static final int LEN_RAW_TEXT = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Duration DUR_TIMEOUT = Duration.ofSeconds(20);

    /**
     * What a source's own time is, per format, because the venues stamp
     * different things: the DSO document the moment the round opens, OKX its
     * ticker, Bybit its response, CoinGecko its last update. Kraken's ticker
     * carries no time at all.
     */
    private static final Map<String, String> MAP_TIME_IS = Map.of(
            "dso", "ROUND_OPENS",
            "okx", "TICKER",
            "bybit", "RESPONSE",
            "coingecko", "UPDATED");

    private static Instant instLastRound;

    private static boolean isScheduleResolved;


    private Prices() {
    }


    /**
     * One price source as prices.json defines it.
     *
     * @param id stable identity
     * @param venue who quotes it: a network or a trading venue
     * @param network MAINNET, TESTNET or DEVNET for a voted rate, else null
     * @param kind VOTED, MARKET or AGGREGATE
     * @param pair what is priced against what
     * @param quote the currency the price is in
     * @param format which reader applies to the body
     * @param url where it is fetched from
     */
    public record PriceDef(String id, String venue, String network, String kind, String pair, String quote,
            String format, String url) {
    }


    /**
     * What one body yielded.
     *
     * @param bid best bid, or null
     * @param ask best ask, or null
     * @param last last price, or the voted rate
     * @param sourceTime the source's own time, or null
     * @param round the mining round of a voted rate, or null
     * @param nodeRaw the part of the body the values were read from
     */
    public record Quote(String bid, String ask, String last, String sourceTime, String round, JsonNode nodeRaw) {
    }


    /** What one GET returned: a status and a body, or the transport failure. */
    record Fetched(int statusHttp, String body, String error, long durationMillis) {
    }


    /**
     * @return every price source this build carries, in file order
     * @throws IOException when the resource is absent or malformed
     */
    public static List<PriceDef> load() throws IOException {
        try (InputStream strmIn = Prices.class.getResourceAsStream(RES_PRICES)) {
            if (strmIn == null)
                throw new IOException("no " + RES_PRICES + " in the artifact");
            JsonNode nodeRoot = MAPPER.readTree(strmIn);
            List<PriceDef> lstDef = new ArrayList<>();
            for (JsonNode nodeOne : nodeRoot.path("prices")) {
                lstDef.add(new PriceDef(text(nodeOne.get("id")), text(nodeOne.get("venue")),
                        text(nodeOne.get("network")), text(nodeOne.get("kind")), text(nodeOne.get("pair")),
                        text(nodeOne.get("quote")), text(nodeOne.get("format")), text(nodeOne.get("url"))));
            }
            return lstDef;
        }
    }


    /**
     * Reads every price source when the interval has elapsed since the last
     * round, and does nothing otherwise. The first call after a start takes the
     * last round from latest.json, so a restart does not read twice inside one
     * interval. Never throws: a price that cannot be read must not stop a
     * publication.
     *
     * @param dirPrices root of the price record
     * @param secInterval seconds between rounds; zero or less reads nothing
     */
    public static void due(Path dirPrices, int secInterval) {
        if (secInterval <= 0)
            return;
        try {
            Instant instNow = Instant.now();
            if (!isScheduleResolved) {
                instLastRound = lastRound(dirPrices);
                isScheduleResolved = true;
            }
            if (instLastRound != null && Duration.between(instLastRound, instNow).toSeconds() < secInterval)
                return;
            instLastRound = instNow;
            List<Map<String, Object>> lstReading = round(load(), instNow, Prices::fetch);
            record(dirPrices, lstReading, instNow);
            int cntOk = 0;
            for (Map<String, Object> mapReading : lstReading) {
                if ("OK".equals(mapReading.get("status")))
                    cntOk++;
            }
            System.out.println("prices: " + cntOk + " of " + lstReading.size() + " read");
        }
        catch (IOException | RuntimeException e) {
            System.err.println("prices: " + e);
        }
    }


    /**
     * Reads every source once, all at the same time.
     *
     * @param lstDef the price sources
     * @param instNow the moment the round is stamped with
     * @param fnFetch how a url is retrieved
     * @return one reading per source, in definition order
     */
    static List<Map<String, Object>> round(List<PriceDef> lstDef, Instant instNow,
            Function<String, CompletableFuture<Fetched>> fnFetch) {
        List<CompletableFuture<Fetched>> lstFuture = new ArrayList<>();
        for (PriceDef def : lstDef) {
            lstFuture.add(fnFetch.apply(def.url()));
        }
        List<Map<String, Object>> lstOut = new ArrayList<>();
        for (int cntDef = 0; cntDef < lstDef.size(); cntDef++) {
            Fetched fetched;
            try {
                fetched = lstFuture.get(cntDef).join();
            }
            catch (RuntimeException e) {
                fetched = new Fetched(-1, null, String.valueOf(e.getMessage()), 0L);
            }
            lstOut.add(reading(lstDef.get(cntDef), fetched, instNow));
        }
        return lstOut;
    }


    /**
     * @param def the source
     * @param fetched what its GET returned
     * @param instNow the moment of the round
     * @return the reading as it is written to the record
     */
    static Map<String, Object> reading(PriceDef def, Fetched fetched, Instant instNow) {
        Map<String, Object> mapOut = new LinkedHashMap<>();
        mapOut.put("id", def.id());
        mapOut.put("readAt", Dataset.iso(instNow));
        mapOut.put("reader", VER_READER);
        mapOut.put("httpStatus", fetched.statusHttp() < 0 ? null : Integer.valueOf(fetched.statusHttp()));
        mapOut.put("durationMillis", Long.valueOf(fetched.durationMillis()));
        if (fetched.error() != null) {
            mapOut.put("status", "TRANSPORT_ERROR");
            mapOut.put("detail", fetched.error());
            return mapOut;
        }
        if (fetched.statusHttp() < 200 || fetched.statusHttp() > 299) {
            mapOut.put("status", "HTTP_ERROR");
            mapOut.put("detail", "status " + fetched.statusHttp());
            mapOut.put("rawText", clip(fetched.body()));
            return mapOut;
        }
        Quote quote;
        try {
            quote = parse(def.format(), fetched.body());
        }
        catch (IOException | IllegalArgumentException e) {
            mapOut.put("status", "UNPARSED");
            mapOut.put("detail", String.valueOf(e.getMessage()));
            mapOut.put("rawText", clip(fetched.body()));
            return mapOut;
        }
        mapOut.put("status", "OK");
        mapOut.put("bid", quote.bid());
        mapOut.put("ask", quote.ask());
        mapOut.put("last", quote.last());
        mapOut.put("sourceTime", quote.sourceTime());
        mapOut.put("round", quote.round());
        mapOut.put("raw", quote.nodeRaw());
        return mapOut;
    }


    /**
     * Reads one body. Each format names exactly the members it reads; anything
     * else in the body is carried in the raw part and not interpreted.
     *
     * @param nameFormat dso, kraken, okx, bybit or coingecko
     * @param textBody the body as retrieved
     * @return what it yielded
     * @throws IOException when the body is not JSON
     * @throws IllegalArgumentException when it is JSON without the members read
     */
    public static Quote parse(String nameFormat, String textBody) throws IOException {
        JsonNode nodeRoot = MAPPER.readTree(textBody);
        if (nodeRoot == null || !nodeRoot.isObject())
            throw new IllegalArgumentException("not a JSON object");
        switch (String.valueOf(nameFormat)) {
            case "dso": {
                JsonNode nodePayload = nodeRoot.path("latest_mining_round").path("contract").path("payload");
                return new Quote(null, null, required(nodePayload, "amuletPrice"),
                        text(nodePayload.get("opensAt")), text(nodePayload.path("round").get("number")),
                        nodePayload);
            }
            case "kraken": {
                JsonNode nodeError = nodeRoot.path("error");
                if (nodeError.isArray() && nodeError.size() > 0)
                    throw new IllegalArgumentException("Kraken error " + nodeError);
                JsonNode nodeResult = nodeRoot.path("result");
                if (!nodeResult.isObject() || nodeResult.size() != 1)
                    throw new IllegalArgumentException("result holds " + nodeResult.size() + " pairs, wanted 1");
                JsonNode nodeTick = nodeResult.elements().next();
                return new Quote(first(nodeTick, "b"), first(nodeTick, "a"), first(nodeTick, "c"), null, null,
                        nodeRoot);
            }
            case "okx": {
                if (!"0".equals(text(nodeRoot.get("code"))))
                    throw new IllegalArgumentException("OKX code " + text(nodeRoot.get("code")) + ": "
                            + text(nodeRoot.get("msg")));
                JsonNode nodeTick = nodeRoot.path("data").path(0);
                return new Quote(required(nodeTick, "bidPx"), required(nodeTick, "askPx"),
                        required(nodeTick, "last"), millis(nodeTick.get("ts")), null, nodeRoot);
            }
            case "bybit": {
                if (!"0".equals(text(nodeRoot.get("retCode"))))
                    throw new IllegalArgumentException("Bybit retCode " + text(nodeRoot.get("retCode")) + ": "
                            + text(nodeRoot.get("retMsg")));
                JsonNode nodeTick = nodeRoot.path("result").path("list").path(0);
                return new Quote(required(nodeTick, "bid1Price"), required(nodeTick, "ask1Price"),
                        required(nodeTick, "lastPrice"), millis(nodeRoot.get("time")), null, nodeRoot);
            }
            case "coingecko": {
                JsonNode nodeCoin = nodeRoot.path("canton-network");
                JsonNode nodeUpdated = nodeCoin.get("last_updated_at");
                String stampUpdated = nodeUpdated == null || !nodeUpdated.canConvertToLong() ? null
                        : Instant.ofEpochSecond(nodeUpdated.asLong()).toString();
                return new Quote(null, null, required(nodeCoin, "usd"), stampUpdated, null, nodeRoot);
            }
            default:
                throw new IllegalArgumentException("no reader for format " + nameFormat);
        }
    }


    /**
     * Appends a round to readings.jsonl, forced to disk, and then rewrites
     * latest.json. The record is written first: a latest.json that ran ahead of
     * the record would publish a reading nothing keeps.
     *
     * @param dirPrices root of the price record
     * @param lstReading the round
     * @param instRound the moment of the round
     * @throws IOException when either write fails
     */
    static void record(Path dirPrices, List<Map<String, Object>> lstReading, Instant instRound) throws IOException {
        Files.createDirectories(dirPrices);
        StringBuilder sbLines = new StringBuilder();
        for (Map<String, Object> mapReading : lstReading) {
            sbLines.append(MAPPER.writeValueAsString(mapReading)).append('\n');
        }
        try (FileOutputStream strmOut = new FileOutputStream(dirPrices.resolve(NAME_READINGS).toFile(), true)) {
            strmOut.write(sbLines.toString().getBytes(StandardCharsets.UTF_8));
            strmOut.flush();
            strmOut.getFD().sync();
        }
        Map<String, Object> mapLatest = latest(dirPrices);
        Map<String, Object> mapById = byId(mapLatest);
        for (Map<String, Object> mapReading : lstReading) {
            merge(mapById, mapReading);
        }
        mapLatest.put("polledAt", Dataset.iso(instRound));
        writeLatest(dirPrices, mapLatest);
    }


    /**
     * What a publication carries: every price source in definition order with
     * its newest successful reading and its newest attempt. The raw part stays
     * in the record and is not published.
     *
     * @param dirPrices root of the price record
     * @param isCollecting whether this instance reads prices at all
     * @param secInterval seconds between rounds
     * @return the published block
     */
    public static Map<String, Object> publication(Path dirPrices, boolean isCollecting, int secInterval) {
        Map<String, Object> mapLatest;
        try {
            mapLatest = Files.isRegularFile(dirPrices.resolve(NAME_LATEST)) ? latestFile(dirPrices) : null;
        }
        catch (IOException e) {
            System.err.println("prices: latest.json unreadable: " + e);
            mapLatest = null;
        }
        return block(mapLatest, Boolean.valueOf(isCollecting && secInterval > 0), Integer.valueOf(secInterval));
    }


    /**
     * The block as the registry alone describes it, every value null. It is
     * what a publication carries when no price has been read, and what the api
     * serves before the first publication exists.
     *
     * @return the published block without readings
     */
    public static Map<String, Object> registryOnly() {
        return block(null, null, null);
    }


    private static Map<String, Object> block(Map<String, Object> mapLatest, Boolean isCollecting,
            Integer secInterval) {
        Map<String, Object> mapById = mapLatest == null ? new LinkedHashMap<>() : byId(mapLatest);
        List<Object> lstOut = new ArrayList<>();
        try {
            for (PriceDef def : load()) {
                Map<String, Object> mapState = castMap(mapById.get(def.id()));
                Map<String, Object> mapOk = mapState == null ? null : castMap(mapState.get("ok"));
                Map<String, Object> mapLast = mapState == null ? null : castMap(mapState.get("last"));
                lstOut.add(Dataset.map(
                        "id", def.id(),
                        "venue", def.venue(),
                        "network", def.network(),
                        "kind", def.kind(),
                        "pair", def.pair(),
                        "quote", def.quote(),
                        "url", def.url(),
                        "bid", get(mapOk, "bid"),
                        "ask", get(mapOk, "ask"),
                        "last", get(mapOk, "last"),
                        "sourceTime", get(mapOk, "sourceTime"),
                        "sourceTimeIs", MAP_TIME_IS.get(def.format()),
                        "round", get(mapOk, "round"),
                        "readAt", get(mapOk, "readAt"),
                        "lastAttemptAt", get(mapLast, "readAt"),
                        "lastAttemptStatus", get(mapLast, "status"),
                        "lastAttemptDetail", get(mapLast, "detail")));
            }
        }
        catch (IOException e) {
            System.err.println("price registry unavailable: " + e);
        }
        return Dataset.map(
                "pollSeconds", secInterval,
                "collecting", isCollecting,
                "polledAt", mapLatest == null ? null : mapLatest.get("polledAt"),
                "prices", lstOut);
    }


    /**
     * @param dirPrices root of the price record
     * @return when the last round ran, or null when none is recorded
     */
    private static Instant lastRound(Path dirPrices) {
        try {
            Object objAt = latest(dirPrices).get("polledAt");
            return objAt == null ? null : Instant.parse(String.valueOf(objAt));
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }


    /**
     * latest.json as it stands, rebuilt from readings.jsonl when it is missing.
     * A line of the record that does not parse - a truncated last line after a
     * hard stop - is skipped rather than costing the lines before it.
     *
     * @param dirPrices root of the price record
     * @return the newest success and the newest attempt per source, and when the
     *         last round ran
     * @throws IOException when either file cannot be read
     */
    static Map<String, Object> latest(Path dirPrices) throws IOException {
        if (Files.isRegularFile(dirPrices.resolve(NAME_LATEST)))
            return latestFile(dirPrices);
        Map<String, Object> mapLatest = new LinkedHashMap<>();
        Map<String, Object> mapById = byId(mapLatest);
        Path fileReadings = dirPrices.resolve(NAME_READINGS);
        if (!Files.isRegularFile(fileReadings))
            return mapLatest;
        String stampLast = null;
        try (BufferedReader rdr = Files.newBufferedReader(fileReadings, StandardCharsets.UTF_8)) {
            String line;
            while ((line = rdr.readLine()) != null) {
                Map<String, Object> mapReading;
                try {
                    mapReading = MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {
                    });
                }
                catch (IOException e) {
                    continue;
                }
                merge(mapById, mapReading);
                stampLast = String.valueOf(mapReading.get("readAt"));
            }
        }
        mapLatest.put("polledAt", stampLast);
        return mapLatest;
    }


    private static Map<String, Object> latestFile(Path dirPrices) throws IOException {
        return MAPPER.readValue(dirPrices.resolve(NAME_LATEST).toFile(),
                new TypeReference<Map<String, Object>>() {
                });
    }


    private static void writeLatest(Path dirPrices, Map<String, Object> mapLatest) throws IOException {
        Path fileTmp = dirPrices.resolve(NAME_LATEST + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(fileTmp.toFile(), mapLatest);
        Files.move(fileTmp, dirPrices.resolve(NAME_LATEST), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }


    /** The newest attempt of a source always; its newest success only when it succeeded. */
    private static void merge(Map<String, Object> mapById, Map<String, Object> mapReading) {
        String idSource = String.valueOf(mapReading.get("id"));
        Map<String, Object> mapState = castMap(mapById.get(idSource));
        if (mapState == null) {
            mapState = new LinkedHashMap<>();
            mapById.put(idSource, mapState);
        }
        Map<String, Object> mapSlim = new LinkedHashMap<>(mapReading);
        mapSlim.remove("raw");
        mapSlim.remove("rawText");
        mapState.put("last", mapSlim);
        if ("OK".equals(mapReading.get("status")))
            mapState.put("ok", mapSlim);
    }


    private static Map<String, Object> byId(Map<String, Object> mapLatest) {
        Map<String, Object> mapById = castMap(mapLatest.get("sources"));
        if (mapById == null) {
            mapById = new LinkedHashMap<>();
            mapLatest.put("sources", mapById);
        }
        return mapById;
    }


    private static CompletableFuture<Fetched> fetch(String url) {
        long msStart = System.currentTimeMillis();
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(DUR_TIMEOUT)
                    .header("User-Agent", AGENT)
                    .header("Accept", "application/json")
                    .build();
        }
        catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new Fetched(-1, null, String.valueOf(e.getMessage()), 0L));
        }
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((resp, exFail) -> {
                    long msTook = System.currentTimeMillis() - msStart;
                    if (exFail != null)
                        return new Fetched(-1, null, String.valueOf(exFail.getMessage()), msTook);
                    return new Fetched(resp.statusCode(), resp.body(), null, msTook);
                });
    }


    private static String required(JsonNode nodeIn, String nameField) {
        String textOut = text(nodeIn.get(nameField));
        if (textOut == null || textOut.isBlank())
            throw new IllegalArgumentException("no " + nameField);
        return textOut;
    }


    private static String first(JsonNode nodeTick, String nameField) {
        JsonNode nodeArr = nodeTick.get(nameField);
        if (nodeArr == null || !nodeArr.isArray() || nodeArr.size() == 0)
            throw new IllegalArgumentException("no " + nameField + "[0]");
        String textOut = text(nodeArr.get(0));
        if (textOut == null || textOut.isBlank())
            throw new IllegalArgumentException("no " + nameField + "[0]");
        return textOut;
    }


    /** A value as text: a string as it stands, a number in plain decimal form. */
    private static String text(JsonNode nodeIn) {
        if (nodeIn == null || nodeIn.isNull() || nodeIn.isMissingNode())
            return null;
        if (nodeIn.isFloatingPointNumber())
            return nodeIn.decimalValue().toPlainString();
        if (nodeIn.isIntegralNumber())
            return nodeIn.bigIntegerValue().toString();
        if (nodeIn.isValueNode())
            return nodeIn.asText();
        return null;
    }


    /** Epoch milliseconds, as a string or a number, in ISO form; null when absent. */
    private static String millis(JsonNode nodeIn) {
        String textMs = text(nodeIn);
        if (textMs == null)
            return null;
        try {
            return Instant.ofEpochMilli(Long.parseLong(textMs)).toString();
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("not epoch milliseconds: " + textMs);
        }
    }


    private static String clip(String textIn) {
        if (textIn == null)
            return null;
        return textIn.length() <= LEN_RAW_TEXT ? textIn : textIn.substring(0, LEN_RAW_TEXT);
    }


    private static Object get(Map<String, Object> mapIn, String nameKey) {
        return mapIn == null ? null : mapIn.get(nameKey);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object objAny) {
        return objAny instanceof Map ? (Map<String, Object>) objAny : null;
    }
}
